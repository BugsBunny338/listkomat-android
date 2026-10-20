package cz.flipcom.listkomat

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import cz.flipcom.listkomat.data.ActiveTicketStore
import cz.flipcom.listkomat.data.CatalogStore
import cz.flipcom.listkomat.data.LocationService
import cz.flipcom.listkomat.model.ActiveTicket
import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.CitySelection
import cz.flipcom.listkomat.model.DurationFormat
import cz.flipcom.listkomat.model.ForeignSimNotice
import cz.flipcom.listkomat.model.LocationState
import cz.flipcom.listkomat.model.NearestCity
import cz.flipcom.listkomat.model.PendingTicket
import cz.flipcom.listkomat.model.Purchase
import cz.flipcom.listkomat.model.PurchaseHandoff
import cz.flipcom.listkomat.model.Ticket
import cz.flipcom.listkomat.model.TicketCatalog
import cz.flipcom.listkomat.model.TicketTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val catalogStore = CatalogStore(app)
    private val activeStore = ActiveTicketStore(app)

    private val _catalog = MutableStateFlow(TicketCatalog.EMPTY)
    val catalog: StateFlow<TicketCatalog> = _catalog

    /** Last remote refresh failed — showing cached/bundled prices. */
    private val _refreshFailed = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = _refreshFailed

    /** Process-wide, so the notification's buttons can change it too. */
    private val tickets = TicketController.get(app)
    val activeTicket: StateFlow<ActiveTicket?> = tickets.active

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
        app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    /** The last manual pick, persisted only as the fallback for when GPS
     *  can't deliver ([CitySelection]). Builds before the rule persisted the
     *  pick under the same key, so it carries over as the fallback. */
    private val _fallbackCityKey = MutableStateFlow(statePrefs.getString(PICKED_CITY_KEY, null))
    private var pickedAtMs: Long? =
        statePrefs.getLong(PICKED_AT_KEY, 0L).takeIf { statePrefs.contains(PICKED_AT_KEY) }

    /** The same pick as a session override of GPS; set again from the
     *  persisted one on a start within the grace window. */
    private val _manualCityKey = MutableStateFlow<String?>(null)

    private val _themeId = MutableStateFlow(statePrefs.getString(THEME_KEY, "clean") ?: "clean")
    val themeId: StateFlow<String> = _themeId

    private val _appearanceMode = MutableStateFlow(statePrefs.getString("appearance", null))
    val appearanceMode: StateFlow<String?> = _appearanceMode

    fun setTheme(id: String) {
        statePrefs.edit().putString(THEME_KEY, id).apply()
        _themeId.value = id
        tickets.refresh()  // the ongoing notification carries the accent
    }

    fun setAppearanceMode(mode: String) {
        statePrefs.edit().putString("appearance", mode).apply()
        _appearanceMode.value = mode
    }

    fun selectCity(key: String) {
        val now = System.currentTimeMillis()
        statePrefs.edit().putString(PICKED_CITY_KEY, key).putLong(PICKED_AT_KEY, now).apply()
        pickedAtMs = now
        _fallbackCityKey.value = key
        _manualCityKey.value = key
    }

    private val _locationState = MutableStateFlow<LocationState>(LocationState.Idle)
    val locationState: StateFlow<LocationState> = _locationState

    /** The city the home screen shows; null = an empty state explains why. */
    val cityKey: StateFlow<String?> =
        combine(_manualCityKey, _locationState, _fallbackCityKey, CitySelection::cityKey)
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                CitySelection.cityKey(_manualCityKey.value, _locationState.value, _fallbackCityKey.value))

    fun onLocationPermission(granted: Boolean) {
        if (granted) locate() else _locationState.value = LocationState.Denied
    }

    /**
     * Every return to the foreground: an old manual pick stops overriding GPS
     * and, with permission, GPS runs again. Process-level, so a rotation (an
     * activity restart) is not a return. Without permission nothing is asked
     * here: the primer is for first runs with nothing to show.
     */
    private fun onAppStarted() {
        _manualCityKey.value = CitySelection.overrideAfterStart(
            _fallbackCityKey.value, pickedAtMs, System.currentTimeMillis())
        val granted = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) locate()
    }

    private val processObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) onAppStarted()
    }

    private var locating: Job? = null

    private fun locate() {
        if (locating?.isActive == true) return
        // A fix from earlier in this process stays on screen while the new
        // one comes in, rather than flashing the searching state.
        if (_locationState.value !is LocationState.Located) {
            _locationState.value = LocationState.Searching
        }
        locating = viewModelScope.launch {
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
        // Drops long-expired tickets (fresh ones stay, so the "expired" state
        // is visible briefly rather than vanishing), re-arms the alarms a
        // force-stop cleared and reposts the ongoing notification.
        tickets.restore()
        viewModelScope.launch {
            val result = catalogStore.refresh(_catalog.value)
            _catalog.value = result.catalog
            _refreshFailed.value = result.failed
        }
        // Last: adding the observer delivers ON_START right away when the app
        // is already started, and onAppStarted needs everything above.
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
    }

    /** Call right before starting the SMS activity; false = a hand-off is
     *  already in flight, don't start another. */
    fun smsHandedOff(city: City, ticket: Ticket): Boolean =
        handoff.armed(PendingTicket(
            cityKey = city.key,
            // Localized like the notification, which resolves it again per render.
            cityName = city.name(getApplication<Application>().resources.configuration.locales[0].language),
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
        tickets.start(ticket)
    }

    fun purchaseDismissed() {
        handoff.resolved()
    }

    /** Confirmation SMS arrived early — re-anchor validity to now. */
    fun confirmNow() = tickets.confirmNow()

    fun endTicket() = tickets.end()

    /** Repost the ongoing notification for the current phase: on resume (the
     *  inexact flip alarm may lag) and once notifications are allowed. */
    fun refreshOngoing() = tickets.refresh()

    companion object {
        /** Also read by TicketController for the notification's accent. */
        const val STATE_PREFS = "app_state"
        const val THEME_KEY = "theme_id"
        private const val PICKED_CITY_KEY = "selected_city"
        private const val PICKED_AT_KEY = "selected_city_at"
    }
}
