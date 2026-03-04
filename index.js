const express = require('express');
const admin = require('firebase-admin');
const axios = require('axios');

const app = express();
app.use(express.json());

// Record exactly when the server started to ignore "Ghost" old rides
const SERVER_START_TIME = Date.now();

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
    console.log("✅ Firebase Admin Connected.");

    activateImperialWatchman();

} catch (error) {
    console.error("❌ FIREBASE INIT FAILED:", error.message);
}

// ==========================================
// 2. THE IMPERIAL VOICE & CLEANER
// ==========================================

function activateImperialWatchman() {
    console.log("🛡️ Imperial Watchman is now on patrol...");

    // 🚨 Listen for NEW ride requests
    db.ref('rides').on('child_added', (snapshot) => {
        const ride = snapshot.val();
        
        // Logic: Only notify if the ride was created AFTER the server turned on
        // This stops old rides from triggering alerts on restart.
        if (ride && ride.status === "REQUESTED" && ride.time > (SERVER_START_TIME - 10000)) {
            console.log(`[Watchman] New Fresh Request from ${ride.pName}. Blasting to drivers.`);
            broadcastToDrivers("🚨 New Dispatch!", `New ${ride.tier} request waiting. Open Radar!`);
        }
    });

    // 🔔 Listen for status changes (Accepted, Arrived, etc)
    db.ref('rides').on('child_changed', (snapshot) => {
        const ride = snapshot.val();
        const status = ride.status;

        if (status === "ACCEPTED") {
            console.log(`[Watchman] Ride accepted by ${ride.driverName}. Notifying passenger.`);
            sendToUser(ride.pName, "Driver Found! 🚕", `${ride.driverName} is on the way.`);
        } else if (status === "ARRIVED") {
            console.log(`[Watchman] Driver arrived for ${ride.pName}.`);
            sendToUser(ride.pName, "Driver Arrived! 🏁", "Your driver is waiting outside.");
        }
    });

    // 🧹 THE CLEANER: Patrolls every 60 seconds
    setInterval(async () => {
        const now = Date.now();
        const timeoutLimit = 5 * 60 * 1000; 
        try {
            const ridesSnap = await db.ref('rides').once('value');
            ridesSnap.forEach((child) => {
                const ride = child.val();
                if (ride.status === "REQUESTED" && ride.time && (now - ride.time) > timeoutLimit) {
                    console.log(`[Cleaner] Removing expired ride: ${child.key}`);
                    sendToUser(ride.pName, "No Drivers Found ⚠️", "Please try requesting again.");
                    child.ref.remove();
                }
            });
        } catch (e) { console.error("[Cleaner] Patrol error:", e.message); }
    }, 60000);
}

// Helper: Send push notification with HIGH PRIORITY for Android
async function sendToUser(userName, title, body) {
    try {
        const userSnap = await db.ref(`users/${userName}`).once('value');
        const token = userSnap.val()?.fcmToken;
        if (token) {
            const message = {
                notification: { title, body },
                token: token,
                android: {
                    priority: "high", // Forces the phone to wake up
                    notification: { sound: "default", channelId: "bayra_alerts" }
                }
            };
            await admin.messaging().send(message);
            console.log(`✅ [Voice] Notified passenger ${userName}`);
        }
    } catch (error) {
        console.error(`❌ [Voice] Error notifying user ${userName}:`, error.message);
    }
}

// Helper: Broadcast to all drivers with HIGH PRIORITY
async function broadcastToDrivers(title, body) {
    try {
        const driversSnap = await db.ref('drivers').once('value');
        let count = 0;
        
        const promises = [];
        driversSnap.forEach((child) => {
            const driver = child.val();
            if (driver.fcmToken) {
                const message = {
                    notification: { title, body },
                    token: driver.fcmToken,
                    android: {
                        priority: "high",
                        notification: { sound: "default", channelId: "bayra_alerts" }
                    }
                };
                promises.push(admin.messaging().send(message));
                count++;
            }
        });
        
        await Promise.all(promises);
        console.log(`✅ [Voice] Dispatch broadcasted to ${count} drivers.`);
    } catch (error) {
        console.error("❌ [Voice] Broadcast failed:", error.message);
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
    try {
        const check = await axios.get(`https://api.chapa.co/v1/transaction/verify/${txRef}`, CHAPA_AUTH);
        if (check.data.status === "success" || check.data.data.status === "success") {
            await db.ref(`rides/${rideId}`).update({ status: "PAID_CHAPA", verifiedByBackend: true });
            const rideSnap = await db.ref(`rides/${rideId}`).once('value');
            const ride = rideSnap.val();
            if (ride) sendToUser(ride.pName, "Payment Verified ✅", "The Treasury has confirmed your payment.");
            res.send("<h1 style='text-align:center; margin-top:20%; color:green; font-family:sans-serif;'>✅ Payment Confirmed! Return to app.</h1>");
        } else {
            res.send("<h1 style='text-align:center; margin-top:20%; color:red;'>🛑 Payment Not Verified.</h1>");
        }
    } catch (error) {
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