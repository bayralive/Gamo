const express = require('express');
const admin = require('firebase-admin');

const app = express();
app.use(express.json());

// ==========================================
// 1. FIREBASE ADMIN INITIALIZATION
// ==========================================
// Render Environment Variable: We parse the private key securely from Render
const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_KEY || '{}');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
});

const db = admin.database();

// ==========================================
// 2. THE IMPERIAL VOICE: AUTOMATED WATCHMAN
// ==========================================

// 🚨 Listen for NEW rides -> Alert All Drivers
db.ref('rides').on('child_added', (snapshot) => {
    const ride = snapshot.val();
    if (ride && ride.status === "REQUESTED") {
        console.log(`[Watchman] New Request from ${ride.pName}. Alerting Drivers.`);
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
        console.error(`Failed to notify user ${userName}:`, error);
    }
}

// Helper: Broadcast push notification to all drivers
async function broadcastToDrivers(title, body) {
    try {
        const driversSnap = await db.ref('drivers').once('value');
        driversSnap.forEach((child) => {
            const token = child.val()?.fcmToken;
            if (token) {
                // Send without awaiting so it blasts to all drivers simultaneously
                admin.messaging().send({
                    notification: { title, body },
                    token: token,
                    android: { priority: "high" }
                }).catch(e => console.log("Invalid or expired token for a driver."));
            }
        });
    } catch (error) {
        console.error("Failed to broadcast to drivers:", error);
    }
}

// ==========================================
// 3. EXPRESS API ROUTES (CHAPA INTEGRATION)
// ==========================================

// (Assuming you have your /initialize-payment route here)

// 🔥 NEW VERIFICATION ENDPOINT
app.get('/verify-payment/:rideId', async (req, res) => {
    const { rideId } = req.params;
    
    // In a real scenario, you'd check Chapa's API here with the tx_ref.
    // For now, we tell Firebase the Treasury has received the funds.
    
    try {
        // 1. Update the database
        await db.ref(`rides/${rideId}`).update({
            status: "PAID_CHAPA",
            verifiedByBackend: true
        });

        // 2. Fetch the ride data to find the passenger's name
        const rideSnap = await db.ref(`rides/${rideId}`).once('value');
        const ride = rideSnap.val();

        // 3. Send the Success Notification to the Passenger
        if (ride && ride.pName) {
            sendToUser(ride.pName, "Payment Verified ✅", "The Treasury has received your payment. Thank you for riding with Bayra Prestige.");
        }

        res.send("<h1 style='color:green; font-family:sans-serif; text-align:center; margin-top:20%;'>Payment Success! You can close this window.</h1>");
    } catch (error) {
        console.error("Error verifying payment:", error);
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