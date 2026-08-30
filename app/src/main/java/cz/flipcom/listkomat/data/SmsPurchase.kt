package cz.flipcom.listkomat.data

import android.content.Intent
import android.net.Uri

/**
 * Builds the hand-off to the user's SMS app. Deliberately ACTION_SENDTO — the
 * same "user presses send themselves" UX as iOS's MFMessageComposeViewController,
 * needing no SMS permission and raising no Play policy flags. Never "upgrade"
 * this to SmsManager/SEND_SMS: Play restricts that permission to default-SMS
 * apps, and the handoff is the product decision anyway.
 */
object SmsPurchase {
    fun intent(smsNumber: String, code: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$smsNumber")).apply {
            putExtra("sms_body", code)
        }
}
