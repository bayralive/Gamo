const express = require('express');
const admin = require('firebase-admin');
const axios = require('axios');

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

// 🔥 IN-APP POPUP ROUTE
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

// 🌐 OFFICIAL CHROME INTENT BRIDGE (GUARANTEED TO LAUNCH APP)
app.get('/reset-password', (req, res) => {
    // Official Android Intent URI that Chrome understands:
    const androidIntentUrl = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.bayra.customer;end";
    
    res.send(`
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Bayra Account Recovery</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding: 40px 20px; background: #f8fafc; color: #1A237E; }
                .card { max-width: 440px; margin: auto; background: white; padding: 35px 25px; border-radius: 20px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); }
                .btn-app { display: block; background: #1A237E; color: #ffffff !important; padding: 16px; text-decoration: none; border-radius: 12px; font-weight: 800; font-size: 15px; margin-top: 25px; box-shadow: 0 4px 12px rgba(26,35,126,0.3); }
                .btn-tg { display: block; background: #229ED9; color: #ffffff !important; padding: 14px; text-decoration: none; border-radius: 12px; font-weight: 700; font-size: 14px; margin-top: 12px; }
            </style>
        </head>
        <body>
            <div class="card">
                <div style="font-size: 40px; margin-bottom: 10px;">🔒</div>
                <h2 style="margin: 0 0 10px 0; color: #1A237E;">Bayra Account Recovery</h2>
                <p style="color: #64748b; font-size: 14px; line-height: 1.5;">Tap below to launch the Bayra Travel app and reset your password:</p>
                
                <!-- 🚀 GUARANTEED CHROME ANDROID LAUNCHER -->
                <a href="${androidIntentUrl}" class="btn-app">OPEN BAYRA TRAVEL APP</a>
                
                <!-- 💬 DIRECT TELEGRAM RECOVERY -->
                <a href="https://t.me/bayratravelchat" class="btn-tg">💬 RESET VIA TELEGRAM SUPPORT</a>
                
                <p style="margin-top: 25px; font-size: 12px; color: #94a3b8;">
                    In the app, tap <strong>"Forgot Password"</strong> to receive your instant code!
                </p>
            </div>
            <script>
                // Auto-trigger app launch on page load
                setTimeout(function() {
                    window.location.href = "${androidIntentUrl}";
                }, 500);
            </script>
        </body>
        </html>
    `);
});

// 🔥 PROMOTIONAL EMAIL & ADMIN GROWTH MONITOR
app.post('/login-security-alert', async (req, res) => {
    const { email, name, status, device } = req.body;

    if (!email || !status) {
        return res.status(400).json({ success: false, error: "Missing required fields" });
    }

    res.status(200).json({ success: true, message: `Security alert queued for ${email}` });

    const isSuccess = status.toUpperCase() === "SUCCESS";
    const subject = isSuccess
        ? "🛡️ Welcome to Bayra Travel – Login Confirmed"
        : "⚠️ Urgent Security Alert: Failed Password Attempt on Bayra Travel";

    const resetLink = "https://bayra-backend-eu.onrender.com/reset-password";

    const htmlContent = isSuccess
        ? `
        <div style="font-family: Arial, sans-serif; max-width: 580px; margin: auto; border: 1px solid #eef0f6; border-radius: 16px; overflow: hidden;">
            <div style="background-color: #1A237E; padding: 30px; text-align: center; color: white;">
                <h1 style="margin: 0; font-size: 24px;">BAYRA TRAVEL</h1>
                <p style="margin: 5px 0 0 0; font-size: 13px; color: #c5cae9;">SOUTHERN ETHIOPIA'S MOST TRUSTED RIDE PLATFORM</p>
            </div>
            <div style="padding: 30px;">
                <h2 style="color: #1A237E; margin-top: 0;">Welcome, ${name || 'Valued Passenger'}! 👋</h2>
                <p style="color: #4a5568; line-height: 1.6;">
                    You have successfully signed in to your <strong>Bayra Travel</strong> account in Arba Minch.
                </p>
                <div style="background-color: #f8fafc; border-left: 4px solid #2e7d32; padding: 14px; margin: 20px 0; border-radius: 6px;">
                    <p style="margin: 0 0 6px 0; color: #2e7d32; font-weight: bold;">✅ Security Status: Confirmed Sign-In</p>
                    <p style="margin: 2px 0; color: #64748b; font-size: 13px;"><strong>Device:</strong> ${device || 'Android Device'}</p>
                    <p style="margin: 2px 0; color: #64748b; font-size: 13px;"><strong>Time:</strong> ${new Date().toLocaleString()}</p>
                </div>
                <div style="text-align: center; margin: 30px 0;">
                    <a href="${resetLink}" style="background-color: #D50000; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 10px; font-weight: bold; display: inline-block; box-shadow: 0 4px 10px rgba(213,0,0,0.3);">
                        🔒 Change Password / Secure Account
                    </a>
                </div>
            </div>
            <div style="background-color: #f8fafc; padding: 16px; text-align: center; border-top: 1px solid #e2e8f0; font-size: 11px; color: #94a3b8;">
                Bayra Travel Technology Hub | Arba Minch, Ethiopia
            </div>
        </div>
        `
        : `
        <div style="font-family: Arial, sans-serif; max-width: 580px; margin: auto; border: 1px solid #fed7d7; border-radius: 16px; overflow: hidden;">
            <div style="background-color: #D50000; padding: 30px; text-align: center; color: white;">
                <h1 style="margin: 0; font-size: 22px;">⚠️ SECURITY WARNING</h1>
            </div>
            <div style="padding: 30px;">
                <h2 style="color: #c53030; margin-top: 0;">Attention ${name || 'Passenger'},</h2>
                <p style="color: #4a5568;">An incorrect password was just entered for your Bayra Travel account.</p>
                <div style="text-align: center; margin: 25px 0;">
                    <a href="${resetLink}" style="background-color: #1A237E; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 10px; font-weight: bold; display: inline-block;">
                        Reset Password
                    </a>
                </div>
            </div>
        </div>
        `;

    try {
        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Travel Security", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: email, name: name || "Passenger" }],
            bcc: [{ email: "bayratraveldonotreplay@gmail.com", name: "Bayra Growth Monitor" }],
            subject: subject,
            htmlContent: htmlContent
        }, {
            headers: {
                'api-key': process.env.BREVO_API_KEY,
                'Content-Type': 'application/json'
            }
        });
        console.log(`✅ [PROMO EMAIL DELIVERED] Sent to ${email}!`);
    } catch (err) {
        console.error("❌ [BREVO ERROR]:", err.response ? err.response.data : err.message);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });
