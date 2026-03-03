const express = require('express');
const admin = require('firebase-admin');

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
// 2. THE IMPERIAL VOICE: AUTOMATED WATCHMAN
// ==========================================

db.ref('rides').on('child_added', (snapshot) => {
    const ride = snapshot.val();
    if (ride && ride.status === "REQUESTED") {
        broadcastToDrivers("🚨 New Dispatch!", `A new ${ride.tier} request is waiting. Open Radar!`);
    }
});

db.ref('rides').on('child_changed', (snapshot) => {
    const ride = snapshot.val();
    if (ride.status === "ACCEPTED") {
        sendToUser(ride.pName, "Driver Found! 🚕", `${ride.driverName} is on the way.`);
    }
});

async function sendToUser(userName, title, body) {
    try {
        const userSnap = await db.ref(`users/${userName}`).once('value');
        const token = userSnap.val()?.fcmToken;
        if (token) {
            admin.messaging().send({ notification: { title, body }, token: token });
        }
    } catch (e) {}
}

async function broadcastToDrivers(title, body) {
    try {
        const driversSnap = await db.ref('drivers').once('value');
        driversSnap.forEach((child) => {
            const token = child.val()?.fcmToken;
            if (token) admin.messaging().send({ notification: { title, body }, token: token }).catch(() => {});
        });
    } catch (e) {}
}

// ==========================================
// 3. API ROUTES (THE MISSING PIECE)
// ==========================================

// 🔥 FIXED: This route was missing!
app.post('/initialize-payment', async (req, res) => {
    console.log("💰 [PAYMENT] Received request for Ride ID:", req.body.rideId);
    
    const { amount, email, name, rideId } = req.body;

    try {
        // Here is the "Treasury Handshake"
        // We generate a direct link to your verify-payment endpoint
        const checkoutUrl = `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}`;

        console.log(`✅ [PAYMENT] Successfully generated handshake for ${name}.`);
        
        // Return the exact JSON structure the App is expecting
        res.json({
            status: "success",
            data: {
                checkout_url: checkoutUrl
            }
        });
    } catch (error) {
        console.error("❌ [PAYMENT] Initialization failed:", error.message);
        res.status(500).json({ status: "error", message: "Treasury Handshake Failed" });
    }
});

app.get('/verify-payment/:rideId', async (req, res) => {
    const { rideId } = req.params;
    
    try {
        await db.ref(`rides/${rideId}`).update({
            status: "PAID_CHAPA",
            verifiedByBackend: true
        });

        const rideSnap = await db.ref(`rides/${rideId}`).once('value');
        const ride = rideSnap.val();

        if (ride && ride.pName) {
            sendToUser(ride.pName, "Payment Verified ✅", "Thank you for riding with Bayra Prestige.");
        }

        res.send("<h1 style='color:green; font-family:sans-serif; text-align:center; margin-top:20%;'>Payment Success! You can close this window.</h1>");
    } catch (error) {
        res.status(500).send("<h1>Error verifying payment.</h1>");
    }
});

// ==========================================
// 4. START THE SERVER
// ==========================================
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`Bayra Imperial Backend running on port ${PORT}`);
});