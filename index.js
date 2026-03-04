const express = require('express');
const admin = require('firebase-admin');
const axios = require('axios');

const app = express();
app.use(express.json());

// ==========================================
// 1. FIREBASE ADMIN INITIALIZATION
// ==========================================
let db;

try {
    if (!process.env.FIREBASE_SERVICE_ACCOUNT_KEY) {
        throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY is missing!");
    }

    const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_KEY);

    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      databaseURL: "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
    });
    
    db = admin.database();
    console.log("✅ Firebase Admin Initialized Successfully.");

} catch (error) {
    console.error("❌ FIREBASE INIT FAILED:", error.message);
}

// ==========================================
// 2. THE IMPERIAL VOICE & CLEANER
// ==========================================

// 🚨 Listen for NEW rides -> Alert All Drivers
db.ref('rides').on('child_added', (snapshot) => {
    const ride = snapshot.val();
    if (ride && ride.status === "REQUESTED") {
        console.log(`[Watchman] New Request from ${ride.pName}. Alerting drivers.`);
        broadcastToDrivers("🚨 New Dispatch!", `A new ${ride.tier} request is waiting. Open Radar!`);
    }
});

// 🔔 Listen for UPDATED rides -> Alert Passenger
db.ref('rides').on('child_changed', (snapshot) => {
    const ride = snapshot.val();
    const status = ride.status;

    if (status === "ACCEPTED") {
        sendToUser(ride.pName, "Driver Found! 🚕", `${ride.driverName} is on the way.`);
    } else if (status === "ARRIVED") {
        sendToUser(ride.pName, "Driver Arrived! 🏁", "Your driver is waiting outside.");
    }
});

// 🔥 THE CLEANER: Patrols every 60 seconds to delete old requests (5-min timeout)
setInterval(async () => {
    const now = Date.now();
    const timeoutLimit = 5 * 60 * 1000; // 5 Minutes
    
    try {
        const ridesSnap = await db.ref('rides').once('value');
        ridesSnap.forEach((child) => {
            const ride = child.val();
            // If ride is still REQUESTED and is older than 5 minutes
            if (ride.status === "REQUESTED" && ride.time && (now - ride.time) > timeoutLimit) {
                console.log(`[Cleaner] Ride ${child.key} expired. Removing from Registry.`);
                
                // Notify Passenger of timeout
                sendToUser(ride.pName, "No Drivers Found ⚠️", "We couldn't find a driver for your request. Please try again.");
                
                // Delete from Database
                child.ref.remove();
            }
        });
    } catch (e) {
        console.error("[Cleaner] Error during patrol:", e.message);
    }
}, 60000); 

// Helper: Send push notification to a specific passenger
async function sendToUser(userName, title, body) {
    try {
        const userSnap = await db.ref(`users/${userName}`).once('value');
        const token = userSnap.val()?.fcmToken;
        if (token) {
            await admin.messaging().send({
                notification: { title, body },
                token: token,
                android: { priority: "high" }
            });
        }
    } catch (error) {
        console.error(`[Voice] Error notifying user ${userName}:`, error.message);
    }
}

// Helper: Broadcast push notification to all online drivers
async function broadcastToDrivers(title, body) {
    try {
        const driversSnap = await db.ref('drivers').once('value');
        driversSnap.forEach((child) => {
            const token = child.val()?.fcmToken;
            if (token) {
                admin.messaging().send({
                    notification: { title, body },
                    token: token,
                    android: { priority: "high" }
                }).catch(() => {}); 
            }
        });
    } catch (error) {
        console.error("[Voice] Broadcast failed:", error.message);
    }
}

// ==========================================
// 3. SECURE CHAPA GATEWAY (THE STEEL VAULT)
// ==========================================

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_AUTH = { headers: { Authorization: `Bearer ${process.env.CHAPA_SECRET_KEY}` } };

app.post('/initialize-payment', async (req, res) => {
    const { amount, email, name, rideId } = req.body;
    const tx_ref = `TX-${rideId}-${Date.now()}`;

    console.log(`💰 [TREASURY] Initializing Chapa for ${name}: ${amount} ETB`);

    try {
        const response = await axios.post(CHAPA_URL, {
            amount: amount,
            currency: "ETB",
            email: email,
            first_name: name,
            tx_ref: tx_ref,
            callback_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
            return_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`
        }, CHAPA_AUTH);

        res.json({ status: "success", data: { checkout_url: response.data.data.checkout_url } });
    } catch (e) {
        console.error("❌ [TREASURY] Handshake failed:", e.message);
        res.status(500).json({ status: "failed", message: "Treasury Offline" });
    }
});

app.get('/verify-payment/:rideId/:txRef', async (req, res) => {
    const { rideId, txRef } = req.params;
    console.log(`🔍 [TREASURY] Interrogating transaction ${txRef}...`);

    try {
        const check = await axios.get(`https://api.chapa.co/v1/transaction/verify/${txRef}`, CHAPA_AUTH);

        if (check.data.status === "success" || check.data.data.status === "success") {
            console.log(`✅ [TREASURY] Funds Confirmed for Ride ${rideId}.`);

            await db.ref(`rides/${rideId}`).update({ status: "PAID_CHAPA", verifiedByBackend: true });

            const rideSnap = await db.ref(`rides/${rideId}`).once('value');
            const ride = rideSnap.val();
            if (ride) sendToUser(ride.pName, "Payment Verified ✅", "The Treasury has confirmed your payment. Thank you.");

            res.send("<h1 style='text-align:center; margin-top:20%; color:green; font-family:sans-serif;'>✅ Payment Confirmed! You may return to the app.</h1>");
        } else {
            console.error(`🛑 [TREASURY] FRAUD DETECTED: Transaction ${txRef} failed verification.`);
            res.send("<h1 style='text-align:center; margin-top:20%; color:red; font-family:sans-serif;'>🛑 Payment Not Verified. Please try again or pay cash.</h1>");
        }
    } catch (error) {
        console.error("❌ [TREASURY] Verification Error:", error.message);
        res.status(500).send("<h1>Verification error.</h1>");
    }
});

// ==========================================
// 4. START THE SERVER
// ==========================================
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`);
});