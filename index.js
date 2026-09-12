const express = require('express');
const admin = require('firebase-admin');
const axios = require('axios');
const nodemailer = require('nodemailer');

const app = express();
app.use(express.json());

const SERVER_START_TIME = Date.now();

// --- FIREBASE INITIALIZATION ---
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

// --- BULLETPROOF GMAIL SETUP (FORCES IPV4 TO STOP 30s TIMEOUT) ---
const transporter = nodemailer.createTransport({
    host: 'smtp.gmail.com',
    port: 465,
    secure: true, // SSL
    auth: {
        user: process.env.EMAIL_USER,
        pass: process.env.EMAIL_PASS
    },
    family: 4, // 🔥 FORCES IPV4 (Stops the 30-second cloud timeout!)
    tls: {
        rejectUnauthorized: false
    }
});

// --- DISPATCH LOGISTICS (IMPERIAL WATCHMAN) ---
function getDistance(lat1, lon1, lat2, lon2) {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
              Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
              Math.sin(dLon/2) * Math.sin(dLon/2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

function activateImperialWatchman() {
    console.log("🛡️ Imperial Watchman is on Smart Dispatch patrol...");
    db.ref('rides').on('child_added', async (snapshot) => {
        const ride = snapshot.val();
        if (ride && ride.status === "REQUESTED" && ride.time > (SERVER_START_TIME - 10000)) {
            const driversSnap = await db.ref('drivers').once('value');
            let closestDriver = null; let minDistance = 9999;

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
                await snapshot.ref.update({
                    reservedFor: closestDriver.name,
                    reservedUntil: Date.now() + 25000
                });
                sendPush(closestDriver.token, "🎯 Exclusive Dispatch!", `Closest driver (${minDistance.toFixed(1)}km)! 25s to accept.`);
                setTimeout(async () => {
                    const currentRide = (await snapshot.ref.once('value')).val();
                    if (currentRide && currentRide.status === "REQUESTED" && currentRide.reservedFor === closestDriver.name) {
                        await snapshot.ref.update({ reservedFor: null });
                        broadcastToDrivers("🚨 New Dispatch!", `New ${currentRide.tier} available for all drivers!`);
                    }
                }, 25000);
            } else {
                broadcastToDrivers("🚨 New Dispatch!", `A new ${ride.tier} request is waiting.`);
            }
        }
    });

    db.ref('rides').on('child_changed', async (snapshot) => {
        const ride = snapshot.val();
        if (!ride) return;
        if (ride.status === "ACCEPTED") sendToUser(ride.pName, "Driver Found! 🚕", `${ride.driverName} is on the way.`);
        else if (ride.status === "ARRIVED") sendToUser(ride.pName, "Driver Arrived! 🏁", "Your driver is waiting outside.");
    });
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
            if (child.val().fcmToken) sendPush(child.val().fcmToken, title, body);
        });
    } catch (e) {}
}

async function sendPush(token, title, body) {
    try {
        await admin.messaging().send({
            notification: { title, body },
            token: token,
            android: { priority: "high", notification: { sound: "default", channelId: "bayra_alerts" } }
        });
    } catch (e) {}
}

// --- CHAPA PAYMENT ROUTES ---
const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_AUTH = { headers: { Authorization: `Bearer ${process.env.CHAPA_SECRET_KEY}` } };

app.post('/initialize-payment', async (req, res) => {
    const { amount, email, name, rideId } = req.body;
    const tx_ref = `TX-${rideId}-${Date.now()}`;
    try {
        const response = await axios.post(CHAPA_URL, {
            amount, currency: "ETB", email, first_name: name, tx_ref,
            callback_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
            return_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`
        }, CHAPA_AUTH);
        res.json({ status: "success", data: { checkout_url: response.data.data.checkout_url } });
    } catch (e) { res.status(500).json({ status: "failed" }); }
});

app.get('/verify-payment/:rideId/:txRef', async (req, res) => {
    const { rideId, txRef } = req.params;
    try {
        const check = await axios.get(`https://api.chapa.co/v1/transaction/verify/${txRef}`, CHAPA_AUTH);
        if (check.data.status === "success" || check.data.data.status === "success") {
            await db.ref(`rides/${rideId}`).update({ status: "PAID_CHAPA", verifiedByBackend: true });
            res.send("<h1 style='text-align:center; margin-top:20%; color:green;'>✅ Payment Confirmed!</h1>");
        } else {
            res.send("<h1 style='text-align:center; margin-top:20%; color:red;'>🛑 Payment Not Verified.</h1>");
        }
    } catch (error) { res.status(500).send("<h1>Verification error.</h1>"); }
});

// 🔥 ROUTE 1: IN-APP POPUP ROUTE
app.post('/send-popup', async (req, res) => {
    const { title, text, imageUrl, popupId } = req.body;
    if (!title || !imageUrl || !popupId) {
        return res.status(400).json({ success: false, error: "Missing title, imageUrl, or popupId" });
    }
    try {
        await db.ref('app_config/active_popup').set({
            id: popupId, title, text, imageUrl, timestamp: Date.now()
        });
        res.status(200).json({ success: true, message: "Pop-up is now live in the app!" });
    } catch (error) { res.status(500).json({ success: false, error: error.message }); }
});

// 🔥 ROUTE 2: FAST, NON-BLOCKING LOGIN SECURITY EMAIL
app.post('/login-security-alert', (req, res) => {
    const { email, name, status, device } = req.body;

    if (!email || !status) {
        return res.status(400).json({ success: false, error: "Missing required fields" });
    }

    // ⚡ INSTANT RESPONSE: Tells Postman "Success" in 0.1s so it NEVER times out!
    res.status(200).json({ success: true, message: `Security alert queued for ${email}` });

    // 📬 Background Email Processing:
    const isSuccess = status.toUpperCase() === "SUCCESS";
    const subject = isSuccess
        ? "🛡️ Bayra Security: Successful Account Login"
        : "⚠️ Bayra Security Alert: Failed Login Attempt";

    const htmlContent = isSuccess
        ? `
            <div style="font-family: Arial, sans-serif; padding: 25px; border: 1px solid #e0e0e0; border-radius: 12px; max-width: 500px; margin: auto;">
                <h2 style="color: #1a237e; margin-top: 0;">Bayra Travel Security</h2>
                <p>Hello <strong>${name || 'Passenger'}</strong>,</p>
                <div style="background-color: #e8f5e9; color: #2e7d32; padding: 12px; border-radius: 8px; font-weight: bold;">
                    ✅ Successful login detected on your account.
                </div>
                <p style="margin-top: 20px;"><strong>Device:</strong> ${device || 'Android Device'}</p>
                <p><strong>Time:</strong> ${new Date().toLocaleString()}</p>
                <hr style="border: 0; border-top: 1px solid #eee; margin: 20px 0;">
                <p style="font-size: 12px; color: gray;">If this was you, you can safely ignore this email. If not, please secure your password.</p>
            </div>
          `
        : `
            <div style="font-family: Arial, sans-serif; padding: 25px; border: 1px solid #ffcdd2; border-radius: 12px; max-width: 500px; margin: auto;">
                <h2 style="color: #d50000; margin-top: 0;">⚠️ Security Warning</h2>
                <p>Hello <strong>${name || 'Passenger'}</strong>,</p>
                <div style="background-color: #ffebee; color: #c62828; padding: 12px; border-radius: 8px; font-weight: bold;">
                    🛑 An incorrect password attempt was just blocked.
                </div>
                <p style="margin-top: 20px;"><strong>Device:</strong> ${device || 'Android Device'}</p>
                <p><strong>Time:</strong> ${new Date().toLocaleString()}</p>
                <hr style="border: 0; border-top: 1px solid #eee; margin: 20px 0;">
                <p style="font-size: 12px; color: gray;">If you forgot your password, please open the Bayra app and use Telegram Password Recovery.</p>
            </div>
          `;

    transporter.sendMail({
        from: `"Bayra Imperial Security" <${process.env.EMAIL_USER}>`,
        to: email,
        subject: subject,
        html: htmlContent
    }).then(() => {
        console.log(`✅ [EMAIL DELIVERED] Security email successfully delivered to ${email}`);
    }).catch((err) => {
        console.error("❌ [EMAIL ERROR]:", err.message);
    });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });
