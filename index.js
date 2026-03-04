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
// 2. LOGISTICS & THE IMPERIAL VOICE
// ==========================================

// Haversine Distance Function: Calculates KM between two GPS points
function getDistance(lat1, lon1, lat2, lon2) {
    const R = 6371; // Radius of earth in KM
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
              Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
              Math.sin(dLon/2) * Math.sin(dLon/2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    return R * c;
}

function activateImperialWatchman() {
    console.log("🛡️ Imperial Watchman is now on High-Speed Smart Dispatch patrol...");

    // 🚨 SMART DISPATCH: Listen for NEW ride requests
    db.ref('rides').on('child_added', async (snapshot) => {
        const ride = snapshot.val();
        
        if (ride && ride.status === "REQUESTED" && ride.time > (SERVER_START_TIME - 10000)) {
            
            const driversSnap = await db.ref('drivers').once('value');
            let closestDriver = null;
            let minDistance = 9999;

            driversSnap.forEach((child) => {
                const driver = child.val();
                if (driver.fcmToken && driver.lat && driver.lon) {
                    const dist = getDistance(ride.pLat, ride.pLon, driver.lat, driver.lon);
                    if (dist < minDistance) {
                        minDistance = dist;
                        closestDriver = { name: child.key, token: driver.fcmToken };
                    }
                }
            });

            if (closestDriver) {
                console.log(`🎯 [Dispatch] Closest driver: ${closestDriver.name}. Reserving for 25s.`);
                
                // 🔥 UPDATED: 25-Second Reservation
                const reservedUntil = Date.now() + 25000;
                await snapshot.ref.update({
                    reservedFor: closestDriver.name,
                    reservedUntil: reservedUntil
                });

                // Notify ONLY the closest driver first (25s countdown in text)
                sendPush(closestDriver.token, "🎯 Exclusive Dispatch!", `Closest driver (${minDistance.toFixed(1)}km)! 25s to accept.`);

                // 🔥 UPDATED: 25-Second Fallback Timer
                setTimeout(async () => {
                    const currentRideSnap = await snapshot.ref.once('value');
                    const currentRide = currentRideSnap.val();
                    
                    if (currentRide && currentRide.status === "REQUESTED" && currentRide.reservedFor === closestDriver.name) {
                        console.log(`🔓 [Dispatch] 25s expired. Opening to all guards.`);
                        await snapshot.ref.update({ reservedFor: null });
                        broadcastToDrivers("🚨 New Dispatch!", `New ${currentRide.tier} available for all drivers!`);
                    }
                }, 25000);

            } else {
                console.log("[Dispatch] No nearby drivers. Global broadcast.");
                broadcastToDrivers("🚨 New Dispatch!", `A new ${ride.tier} request is waiting.`);
            }
        }
    });

    db.ref('rides').on('child_changed', async (snapshot) => {
        const ride = snapshot.val();
        const status = ride.status;

        if (status === "ACCEPTED") {
            sendToUser(ride.pName, "Driver Found! 🚕", `${ride.driverName} is on the way.`);
        } else if (status === "ARRIVED") {
            sendToUser(ride.pName, "Driver Arrived! 🏁", "Your driver is waiting outside.");
        } 
        else if (status === "CANCELLED_BY_DRIVER") {
            await auditDriverConduct(ride.driverName, snapshot.key);
            sendToUser(ride.pName, "Ride Cancelled ⚠️", "Your driver had an issue. Please request again.");
        } 
        else if (status === "CANCELLED_BY_PASSENGER") {
            sendToUser(ride.driverName, "Passenger Cancelled 🛑", "The passenger has cancelled the request.");
        }
    });

    setInterval(async () => {
        const now = Date.now();
        const timeoutLimit = 5 * 60 * 1000; 
        try {
            const ridesSnap = await db.ref('rides').once('value');
            ridesSnap.forEach((child) => {
                const ride = child.val();
                if (ride.status === "REQUESTED" && ride.time && (now - ride.time) > timeoutLimit) {
                    child.ref.remove();
                }
            });
        } catch (e) {}
    }, 60000);
}

async function auditDriverConduct(driverName, rideId) {
    try {
        const driverRef = db.ref(`drivers/${driverName}`);
        const strikesRef = driverRef.child('strikes');
        await strikesRef.transaction((current) => (current || 0) + 1);
        const snap = await strikesRef.once('value');
        if (snap.val() >= 3) {
            const banUntil = Date.now() + (24 * 60 * 60 * 1000);
            await driverRef.update({ isBanned: true, banUntil: banUntil, strikes: 0 });
        }
    } catch (e) {}
}

async function sendToUser(userName, title, body) {
    try {
        const userSnap = await db.ref(`users/${userName}`).once('value');
        const token = userSnap.val()?.fcmToken;
        if (token) sendPush(token, title, body);
    } catch (e) {}
}

async function broadcastToDrivers(title, body) {
    try {
        const driversSnap = await db.ref('drivers').once('value');
        driversSnap.forEach((child) => {
            const driver = child.val();
            if (driver.fcmToken) sendPush(driver.fcmToken, title, body);
        });
    } catch (e) {}
}

async function sendPush(token, title, body) {
    try {
        await admin.messaging().send({
            notification: { title, body },
            token: token,
            android: {
                priority: "high",
                notification: { sound: "default", channelId: "bayra_alerts" }
            }
        });
    } catch (e) {}
}

// ==========================================
// 3. SECURE CHAPA GATEWAY (THE STEEL VAULT)
// ==========================================

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_AUTH = { headers: { Authorization: `Bearer ${process.env.CHAPA_SECRET_KEY}` } };

app.post('/initialize-payment', async (req, res) => {
    const { amount, email, name, rideId } = req.body;
    const tx_ref = `TX-${rideId}-${Date.now()}`;
    try {
        const response = await axios.post(CHAPA_URL, {
            amount: amount, currency: "ETB", email: email, first_name: name, tx_ref: tx_ref,
            callback_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
            return_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`
        }, CHAPA_AUTH);
        res.json({ status: "success", data: { checkout_url: response.data.data.checkout_url } });
    } catch (e) {
        res.status(500).json({ status: "failed" });
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
            res.send("<h1 style='text-align:center; margin-top:20%; color:green;'>✅ Payment Confirmed! Return to app.</h1>");
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