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

// 🔥 ENHANCED PROMOTIONAL SECURITY EMAIL & LIVE GROWTH TRACKER
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

    const htmlContent = isSuccess
        ? `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
        </head>
        <body style="margin: 0; padding: 0; background-color: #f4f6fb; font-family: 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
            <table width="100%" cellpadding="0" cellspacing="0" style="padding: 30px 10px;">
                <tr>
                    <td align="center">
                        <table width="100%" max-width="580" style="max-width: 580px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.06); border: 1px solid #eef0f6;">
                            
                            <!-- Header Banner -->
                            <tr>
                                <td style="background: linear-gradient(135deg, #1A237E 0%, #283593 100%); padding: 35px 30px; text-align: center;">
                                    <h1 style="color: #ffffff; margin: 0; font-size: 26px; font-weight: 800; letter-spacing: 1px;">BAYRA TRAVEL</h1>
                                    <p style="color: #c5cae9; margin: 6px 0 0 0; font-size: 13px; letter-spacing: 0.5px;">SOUTHERN ETHIOPIA'S MOST TRUSTED RIDE PLATFORM</p>
                                </td>
                            </tr>

                            <!-- Body Content -->
                            <tr>
                                <td style="padding: 35px 30px;">
                                    <h2 style="color: #1A237E; margin-top: 0; font-size: 20px;">Welcome back, ${name || 'Valued Passenger'}! 👋</h2>
                                    <p style="color: #4a5568; font-size: 15px; line-height: 1.6; margin-bottom: 22px;">
                                        You have successfully signed in to your <strong>Bayra Travel</strong> account. From the springs of Arba Minch to the heights of Nech Sar, our premium network of verified drivers is ready to deliver a safe, dignified, and metered journey every single time.
                                    </p>

                                    <!-- Security Status Card -->
                                    <div style="background-color: #f8fafc; border-left: 4px solid #2e7d32; border-radius: 8px; padding: 16px 20px; margin-bottom: 25px;">
                                        <p style="margin: 0 0 8px 0; color: #2e7d32; font-weight: 700; font-size: 14px;">
                                            ✅ Security Verification: Successful Sign-In
                                        </p>
                                        <p style="margin: 4px 0; color: #64748b; font-size: 13px;"><strong>Device:</strong> ${device || 'Android Smartphone'}</p>
                                        <p style="margin: 4px 0; color: #64748b; font-size: 13px;"><strong>Time:</strong> ${new Date().toLocaleString('en-US', { timeZone: 'Africa/Addis_Ababa' })} (East Africa Time)</p>
                                        <p style="margin: 4px 0; color: #64748b; font-size: 13px;"><strong>Location:</strong> Arba Minch & Southern Region Network</p>
                                    </div>

                                    <!-- Call To Action: Reset Password Button -->
                                    <div style="text-align: center; margin: 35px 0 25px 0;">
                                        <p style="font-size: 13px; color: #94a3b8; margin-bottom: 12px;">Did you not perform this login?</p>
                                        <a href="https://t.me/bayratravelchat" style="background-color: #D50000; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 10px; font-weight: 700; font-size: 14px; display: inline-block; box-shadow: 0 4px 12px rgba(213,0,0,0.25);">
                                            🔒 Change Password / Secure Account
                                        </a>
                                    </div>

                                    <!-- Promotional Perks -->
                                    <table width="100%" style="margin-top: 30px; padding-top: 20px; border-top: 1px solid #f1f5f9;">
                                        <tr>
                                            <td width="33%" align="center" style="padding: 10px;">
                                                <p style="font-size: 20px; margin: 0;">🛡️</p>
                                                <p style="font-size: 12px; font-weight: 700; color: #1A237E; margin: 4px 0;">Live GPS Tracked</p>
                                            </td>
                                            <td width="33%" align="center" style="padding: 10px;">
                                                <p style="font-size: 20px; margin: 0;">⚖️</p>
                                                <p style="font-size: 12px; font-weight: 700; color: #1A237E; margin: 4px 0;">Zero Price Haggling</p>
                                            </td>
                                            <td width="33%" align="center" style="padding: 10px;">
                                                <p style="font-size: 20px; margin: 0;">🚗</p>
                                                <p style="font-size: 12px; font-weight: 700; color: #1A237E; margin: 4px 0;">Bajaj to Code 3</p>
                                            </td>
                                        </tr>
                                    </table>

                                </td>
                            </tr>

                            <!-- Corporate Footer -->
                            <tr>
                                <td style="background-color: #f8fafc; padding: 22px 30px; text-align: center; border-top: 1px solid #e2e8f0;">
                                    <p style="font-size: 12px; color: #94a3b8; margin: 0;">
                                        <em>"Sarotethai nuna maaddo, Aadhidatethai nuna kaaletho."</em>
                                    </p>
                                    <p style="font-size: 11px; color: #94a3b8; margin: 6px 0 0 0;">
                                        Bayra Travel Technology Hub | Arba Minch, Ethiopia
                                    </p>
                                    <p style="font-size: 11px; color: #cbd5e1; margin: 4px 0 0 0;">
                                        Official Support: bayratraveldonotreplay@gmail.com
                                    </p>
                                </td>
                            </tr>

                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        `
        : `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
        </head>
        <body style="margin: 0; padding: 0; background-color: #fff5f5; font-family: 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
            <table width="100%" cellpadding="0" cellspacing="0" style="padding: 30px 10px;">
                <tr>
                    <td align="center">
                        <table width="100%" style="max-width: 580px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.06); border: 1px solid #fed7d7;">
                            
                            <!-- Header Warning Banner -->
                            <tr>
                                <td style="background: linear-gradient(135deg, #D50000 0%, #b71c1c 100%); padding: 30px; text-align: center;">
                                    <h1 style="color: #ffffff; margin: 0; font-size: 24px; font-weight: 800;">⚠️ BAYRA SECURITY WARNING</h1>
                                    <p style="color: #ffcdd2; margin: 6px 0 0 0; font-size: 13px;">UNAUTHORIZED LOGIN ATTEMPT BLOCKED</p>
                                </td>
                            </tr>

                            <!-- Body Content -->
                            <tr>
                                <td style="padding: 35px 30px;">
                                    <h2 style="color: #c53030; margin-top: 0; font-size: 19px;">Attention ${name || 'Passenger'},</h2>
                                    <p style="color: #4a5568; font-size: 15px; line-height: 1.6;">
                                        An incorrect password was just entered to access your Bayra Travel account. For your safety, our security shield blocked the attempt immediately.
                                    </p>

                                    <!-- Incident Details -->
                                    <div style="background-color: #fff5f5; border-left: 4px solid #D50000; border-radius: 8px; padding: 16px 20px; margin: 25px 0;">
                                        <p style="margin: 4px 0; color: #742a2a; font-size: 13px;"><strong>Attempted On Device:</strong> ${device || 'Unknown Device'}</p>
                                        <p style="margin: 4px 0; color: #742a2a; font-size: 13px;"><strong>Time:</strong> ${new Date().toLocaleString('en-US', { timeZone: 'Africa/Addis_Ababa' })} (EAT)</p>
                                    </div>

                                    <!-- Recovery CTA -->
                                    <div style="text-align: center; margin: 30px 0 20px 0;">
                                        <a href="https://t.me/bayratravelchat" style="background-color: #1A237E; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 10px; font-weight: 700; font-size: 14px; display: inline-block;">
                                            Reset Password via Telegram Support
                                        </a>
                                    </div>
                                </td>
                            </tr>

                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #f8fafc; padding: 20px; text-align: center; border-top: 1px solid #edf2f7;">
                                    <p style="font-size: 11px; color: #a0aec0; margin: 0;">Bayra Travel Digital Guardian | Protecting Arba Minch Passengers</p>
                                </td>
                            </tr>

                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        `;

    try {
        // 🚀 LIVE GROWTH TRACKER: Delivers to Passenger AND BCCs Admin simultaneously!
        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Travel Security", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: email, name: name || "Passenger" }],
            bcc: [{ email: "bayratraveldonotreplay@gmail.com", name: "Bayra Growth Monitor" }], // 📈 Auto-monitors company growth!
            subject: subject,
            htmlContent: htmlContent
        }, {
            headers: {
                'api-key': process.env.BREVO_API_KEY,
                'Content-Type': 'application/json'
            }
        });
        console.log(`✅ [PROMO EMAIL & ADMIN TRACKER DELIVERED] Sent to ${email} + Copied to Admin!`);
    } catch (err) {
        console.error("❌ [BREVO ERROR]:", err.response ? err.response.data : err.message);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });
