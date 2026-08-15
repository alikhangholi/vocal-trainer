package com.barnamechi.betterpitch.billing

import androidx.activity.ComponentActivity
import com.barnamechi.betterpitch.BuildConfig
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.exception.BazaarNotFoundException
import ir.cafebazaar.poolakey.request.PurchaseRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the billing connection is currently doing, for the unlock surface to render. */
sealed class BillingStatus {
    object Idle : BillingStatus()
    object Connecting : BillingStatus()
    object Ready : BillingStatus()

    /** Cafe Bazaar isn't installed - subscribing is impossible, but the free tier must still work. */
    object BazaarMissing : BillingStatus()
    data class Error(val message: String) : BillingStatus()
}

/**
 * Thin wrapper over Poolakey (Cafe Bazaar in-app billing).
 *
 * Owned by [com.barnamechi.betterpitch.MainActivity] the same way ToneEngine/Metronome are - no DI,
 * no ViewModel. Verification is client-side only (v1 has no backend): Poolakey checks Bazaar's
 * signature against the RSA public key injected into BuildConfig at build time.
 */
class BillingManager(private val activity: ComponentActivity) {

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _status = MutableStateFlow<BillingStatus>(BillingStatus.Idle)
    val status: StateFlow<BillingStatus> = _status.asStateFlow()

    // Builds without the secret (local/CI debug) fall back to Disable rather than handing Poolakey
    // an empty key, which would fail every verification.
    private val securityCheck: SecurityCheck =
        if (BuildConfig.BAZAAR_RSA_KEY.isNotBlank()) {
            SecurityCheck.Enable(rsaPublicKey = BuildConfig.BAZAAR_RSA_KEY)
        } else {
            SecurityCheck.Disable
        }

    private val payment = Payment(
        context = activity,
        config = PaymentConfiguration(localSecurityCheck = securityCheck)
    )

    private var connection: Connection? = null

    // Each subscribeProduct call registers a fresh launcher under Poolakey's single fixed
    // registry key, so a double-tap on Unlock must not reach the library.
    private var purchaseInFlight = false

    /** Safe to call again after a failure - that's how the unlock panel's retry works. */
    fun connect() {
        if (_status.value == BillingStatus.Connecting || _status.value == BillingStatus.Ready) return
        // Set before connecting: ServiceBillingConnection reports "Bazaar not installed" synchronously
        // from inside payment.connect, so this must not overwrite the failure it produces.
        _status.value = BillingStatus.Connecting
        connection = payment.connect {
            connectionSucceed {
                _status.value = BillingStatus.Ready
                queryPurchasedSubscriptions()
            }
            connectionFailed { throwable ->
                _status.value = statusFor(throwable, "Could not reach Cafe Bazaar.")
            }
            disconnected {
                if (_status.value == BillingStatus.Ready) _status.value = BillingStatus.Idle
            }
        }
    }

    fun isReady(): Boolean = _status.value == BillingStatus.Ready

    /** Runs on every successful connect, so a reinstall or relaunch restores premium for free. */
    fun queryPurchasedSubscriptions() {
        payment.getSubscribedProducts {
            querySucceed { purchases ->
                _isPremium.value = purchases.any { it.productId == BuildConfig.SUBSCRIPTION_SKU }
            }
            queryFailed {
                // Leave isPremium as-is; a failed query must not silently revoke a paid user.
            }
        }
    }

    /**
     * Launches Bazaar's subscription sheet. The whole flow - sheet result included - is reported
     * through this one callback: Poolakey drives it off the activity's ActivityResultRegistry, so
     * there is nothing for MainActivity to forward.
     */
    fun subscribe() {
        if (purchaseInFlight) return
        // Subscribing while disconnected only surfaces as an IllegalStateException on the connection
        // callback, so retry the connection instead; the panel then offers Subscribe once it's Ready.
        if (!isReady()) { connect(); return }
        purchaseInFlight = true
        payment.subscribeProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(productId = BuildConfig.SUBSCRIPTION_SKU)
        ) {
            purchaseFlowBegan {
                // Bazaar's sheet is up; one of the three outcomes below follows.
            }
            failedToBeginFlow { throwable ->
                purchaseInFlight = false
                _status.value = statusFor(throwable, "Could not start the purchase.")
            }
            purchaseSucceed {
                purchaseInFlight = false
                _isPremium.value = true
                _status.value = BillingStatus.Ready
            }
            purchaseCanceled {
                // User backed out - nothing to report.
                purchaseInFlight = false
            }
            purchaseFailed { throwable ->
                purchaseInFlight = false
                _status.value = BillingStatus.Error(throwable.message ?: "The purchase failed.")
            }
        }
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
    }

    /** "Bazaar isn't installed" is the one failure the UI reacts to; the rest just get shown. */
    private fun statusFor(throwable: Throwable, fallback: String): BillingStatus =
        if (throwable is BazaarNotFoundException) BillingStatus.BazaarMissing
        else BillingStatus.Error(throwable.message ?: fallback)
}
