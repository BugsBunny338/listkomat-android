package cz.flipcom.listkomat

import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.flipcom.listkomat.data.ActiveTicketStore
import cz.flipcom.listkomat.data.CatalogStore
import cz.flipcom.listkomat.data.LocationService
import cz.flipcom.listkomat.notify.TicketNotifications
import cz.flipcom.listkomat.model.ActiveTicket
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.DurationFormat
import cz.flipcom.listkomat.model.ForeignSimNotice
import cz.flipcom.listkomat.model.NearestCity
import cz.flipcom.listkomat.model.PendingTicket
import cz.flipcom.listkomat.model.Purchase
import cz.flipcom.listkomat.model.PurchaseHandoff
import cz.flipcom.listkomat.model.Ticket
import cz.flipcom.listkomat.model.TicketCatalog
import cz.flipcom.listkomat.model.TicketTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val catalogStore = CatalogStore(app)
    private val activeStore = ActiveTicketStore(app)

    private val _catalog = MutableStateFlow(TicketCatalog.EMPTY)
    val catalog: StateFlow<TicketCatalog> = _catalog

    /** Last remote refresh failed — showing cached/bundled prices. */
    private val _refreshFailed = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = _refreshFailed

    private val _activeTicket = MutableStateFlow<ActiveTicket?>(null)
    val activeTicket: StateFlow<ActiveTicket?> = _activeTicket

    /**
     * The SMS hand-off in flight. ACTION_SENDTO reports nothing back, so asking
     * the user is the only honest signal — but only once the SMS app has
     * covered us and we are resumed again ([Purchase.AwaitingAnswer]); the UI
     * feeds pause/resume in via [handOffLeft]/[handOffReturned]. Persisted
     * from the moment it is armed, so even if the process dies while the user
     * is in the SMS app we still ask when the app is next opened, provided
     * that is within 10 minutes of the hand-off.
     */
    private val handoff = PurchaseHandoff(activeStore, System::currentTimeMillis)
    val purchase: StateFlow<Purchase?> = handoff.state

    private val noticePrefs =
        app.getSharedPreferences("foreign_sim_notice", Context.MODE_PRIVATE)

    private val statePrefs =
        app.getSharedPreferences("app_state", Context.MODE_PRIVATE)

    /** Manual city selection, persisted. Null until the user picks one —
     *  the GPS-nearest default the iOS app has is a future feature here. */
    private val _selectedCityKey =
        MutableStateFlow(statePrefs.getString("selected_city", null))
    val selectedCityKey: StateFlow<String?> = _selectedCityKey

    private val _themeId = MutableStateFlow(statePrefs.getString("theme_id", "clean") ?: "clean")
    val themeId: StateFlow<String> = _themeId

    private val _appearanceMode = MutableStateFlow(statePrefs.getString("appearance", null))
    val appearanceMode: StateFlow<String?> = _appearanceMode

    fun setTheme(id: String) {
        statePrefs.edit().putString("theme_id", id).apply()
        _themeId.value = id
    }

    fun setAppearanceMode(mode: String) {
        statePrefs.edit().putString("appearance", mode).apply()
        _appearanceMode.value = mode
    }

    fun selectCity(key: String) {
        statePrefs.edit().putString("selected_city", key).apply()
        _selectedCityKey.value = key
    }

    /** GPS default, mirroring iOS: nearest supported city + distance, or a
     *  denied/searching state. Manual selection always wins over this. */
    sealed interface LocationState {
        data object Idle : LocationState
        data object Searching : LocationState
        data object Denied : LocationState
        data class Located(val cityKey: String, val distanceKm: Double) : LocationState
    }

    private val _locationState = MutableStateFlow<LocationState>(LocationState.Idle)
    val locationState: StateFlow<LocationState> = _locationState

    fun onLocationPermission(granted: Boolean) {
        if (!granted) {
            _locationState.value = LocationState.Denied
            return
        }
        _locationState.value = LocationState.Searching
        viewModelScope.launch {
            val fix = runCatching { LocationService.coarseFix(getApplication()) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            val nearest = fix?.let { NearestCity.nearest(it.latitude, it.longitude, _catalog.value.cities) }
            // No fix is NOT "denied" — iOS keeps the searching state (with the
            // manual pick button right there) rather than blaming permissions.
            if (nearest != null) {
                _locationState.value = LocationState.Located(nearest.city.key, nearest.distanceKm)
            }
        }
    }

    private val _simNoticeDismissed = MutableStateFlow(noticePrefs.getBoolean("dismissed", false))
    val simNoticeDismissed: StateFlow<Boolean> = _simNoticeDismissed

    /** ISO country of the SIM, lowercase, or "" when there is none (emulator). */
    val simCountryIso: String =
        runCatching { app.getSystemService(TelephonyManager::class.java)?.simCountryIso }
            .getOrNull().orEmpty()

    fun shouldShowSimNotice(uiLanguage: String?): Boolean =
        ForeignSimNotice.shouldShow(uiLanguage, simCountryIso, _simNoticeDismissed.value)

    fun dismissSimNotice() {
        noticePrefs.edit().putBoolean("dismissed", true).apply()
        _simNoticeDismissed.value = true
    }

    init {
        _catalog.value = catalogStore.loadCachedOrBundled()
        _activeTicket.value = activeStore.load()?.takeIf {
            // Drop long-expired tickets on launch; keep fresh ones so the
            // "expired" state is visible briefly rather than vanishing.
            System.currentTimeMillis() < it.timeline.endMs + EXPIRED_KEEP_MS
        }
        viewModelScope.launch {
            val result = catalogStore.refresh(_catalog.value)
            _catalog.value = result.catalog
            _refreshFailed.value = result.failed
        }
    }

    /** Call right before starting the SMS activity; false = a hand-off is
     *  already in flight, don't start another. */
    fun smsHandedOff(city: City, ticket: Ticket): Boolean =
        handoff.armed(PendingTicket(
            cityKey = city.key,
            cityName = city.name,
            ticketCode = ticket.code,
            durationMinutes = ticket.durationMinutes,
            priceKc = ticket.priceKc,
            armedAt = System.currentTimeMillis(),
        ))

    fun handOffLeft() = handoff.left()
    fun handOffReturned() = handoff.returned()
    fun handOffDeadlineIn(): Long? = handoff.handOffDeadlineIn()
    fun handOffTimedOut() = handoff.timedOut()
    fun handOffFailed() = handoff.failed()
    fun handOffFailureAcknowledged() { handoff.resolved() }

    /** User confirmed they sent the SMS — start the validity countdown. */
    fun purchaseConfirmed() {
        val asked = handoff.resolved() ?: return
        val pending = asked.pending
        val ticket = ActiveTicket(
            cityKey = pending.cityKey,
            cityName = pending.cityName,
            ticketLabel = DurationFormat.format(getApplication(), pending.durationMinutes),
            priceKc = pending.priceKc,
            timeline = TicketTimeline.make(
                sentAtMs = asked.sentAtMs,
                durationMinutes = pending.durationMinutes,
            ),
        )
        activeStore.save(ticket)
        _activeTicket.value = ticket
        TicketNotifications.scheduleExpiry(getApplication(), ticket)
    }

    fun purchaseDismissed() {
        handoff.resolved()
    }

    /** Confirmation SMS arrived early — re-anchor validity to now. */
    fun confirmNow() {
        val current = _activeTicket.value ?: return
        val updated = current.copy(timeline = current.timeline.confirmed(System.currentTimeMillis()))
        activeStore.save(updated)
        _activeTicket.value = updated
        TicketNotifications.scheduleExpiry(getApplication(), updated)
    }

    fun endTicket() {
        activeStore.clear()
        _activeTicket.value = null
        TicketNotifications.cancelExpiry(getApplication())
    }

    private companion object {
        /** How long an expired ticket stays visible after endMs. */
        const val EXPIRED_KEEP_MS: Long = 60 * 60_000
    }
}
