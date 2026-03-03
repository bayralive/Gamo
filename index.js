const express = require('express');
const admin = require('firebase-admin');
const axios = require('axios'); // Tool to talk to Chapa

const app = express();
app.use(express.json());

// ==========================================
// 1. FIREBASE ADMIN INITIALIZATION
// ==========================================
let db;
try {
    const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_KEY);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      databaseURL: "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
    });
    db = admin.database();
    console.log("✅ Firebase Admin Initialized.");
} catch (error) {
    console.error("❌ FIREBASE INIT FAILED:", error.message);
    process.exit(1);
}

// ==========================================
// 2. THE IMPERIAL VOICE: WATCHMAN
// ==========================================
// (Keep your existing Watchman logic for ACCEPTED and ARRIVED here)
// ...

async function sendToUser(userName, title, body) {
    const userSnap = await db.ref(`users/${userName}`).once('value');
    const token = userSnap.val()?.fcmToken;
    if (token) admin.messaging().send({ notification: { title, body }, token: token });
}

// ==========================================
// 3. SECURE CHAPA GATEWAY
// ==========================================

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_AUTH = { headers: { Authorization: `Bearer ${process.env.CHAPA_SECRET_KEY}` } };

app.post('/initialize-payment', async (req, res) => {
    const { amount, email, name, rideId } = req.body;
    const tx_ref = `TX-${rideId}-${Date.now()}`; // Unique ID for this payment

    console.log(`💰 [PAYMENT] Initializing real Chapa request for ${name}: ${amount} ETB`);

    try {
        const response = await axios.post(CHAPA_URL, {
            amount: amount,
            currency: "ETB",
            email: email,
            first_name: name,
            tx_ref: tx_ref,
            callback_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
            return_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
            "customization[title]": "Bayra Prestige Ride",
            "customization[description]": "Payment for your elite journey"
        }, CHAPA_AUTH);

        // Chapa gives us a real checkout_url
        res.json({ status: "success", data: { checkout_url: response.data.data.checkout_url } });
        
    } catch (e) {
        console.error("❌ [CHAPA] Failed to get link:", e.response?.data || e.message);
        res.status(500).json({ status: "failed", message: "Chapa Gateway Offline" });
    }
});

// 🔥 THE MASTER VERIFIER (NO MONEY, NO RIDE)
app.get('/verify-payment/:rideId/:txRef', async (req, res) => {
    const { rideId, txRef } = req.params;

    console.log(`🔍 [TREASURY] Verifying transaction ${txRef}...`);

    try {
        // CALL CHAPA DIRECTLY to confirm the money is in your account
        const check = await axios.get(`https://api.chapa.co/v1/transaction/verify/${txRef}`, CHAPA_AUTH);

        if (check.data.status === "success" || check.data.data.status === "success") {
            console.log(`✅ [TREASURY] Money confirmed for Ride ${rideId}!`);

            // ONLY NOW DO WE TELL THE DRIVER
            await db.ref(`rides/${rideId}`).update({ status: "PAID_CHAPA", verifiedByBackend: true });

            const rideSnap = await db.ref(`rides/${rideId}`).once('value');
            const ride = rideSnap.val();
            if (ride) sendToUser(ride.pName, "Payment Verified ✅", "The Imperial Treasury has received your funds.");

            res.send("<h1 style='text-align:center; margin-top:20%; color:green; font-family:sans-serif;'>✅ Payment Confirmed! Your ride is officially paid. You may return to the app.</h1>");
        } else {
            console.error(`🛑 [TREASURY] FRAUD ALERT: Transaction ${txRef} failed verification.`);
            res.send("<h1 style='text-align:center; margin-top:20%; color:red; font-family:sans-serif;'>🛑 Payment Failed. Please try again or pay cash.</h1>");
        }
    } catch (e) {
        console.error("❌ [TREASURY] Verification error:", e.response?.data || e.message);
        res.send("<h1>Verification Error. Contact Support.</h1>");
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`Secure Backend running on port ${PORT}`));