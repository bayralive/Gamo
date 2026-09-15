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

// 🌐 DIRECT INTENT BRIDGE
app.get('/reset-password', (req, res) => {
    const androidIntentUrl = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.bayra.customer;B.recover=true;S.route=recovery;end";
    res.send(`
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Bayra Account Recovery</title>
            <style>
                body { font-family: sans-serif; text-align: center; padding: 40px 20px; background: #f8fafc; color: #1A237E; }
                .card { max-width: 440px; margin: auto; background: white; padding: 35px 25px; border-radius: 20px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); }
                .btn-app { display: block; background: #1A237E; color: #ffffff !important; padding: 16px; text-decoration: none; border-radius: 12px; font-weight: 800; font-size: 15px; margin-top: 25px; }
            </style>
        </head>
        <body>
            <div class="card">
                <div style="font-size: 40px; margin-bottom: 10px;">🔒</div>
                <h2>Bayra Password Recovery</h2>
                <p style="color: #64748b;">Opening password recovery directly in your Bayra app...</p>
                <a href="${androidIntentUrl}" class="btn-app">OPEN PASSWORD RECOVERY SCREEN</a>
            </div>
            <script>
                setTimeout(function() { window.location.href = "${androidIntentUrl}"; }, 400);
            </script>
        </body>
        </html>
    `);
});

// 🔥 DUAL DISPATCH: CUSTOMER EMAIL + SEQUENTIAL DIRECTOR EXECUTIVE BRIEFING
app.post('/login-security-alert', async (req, res) => {
    const { email, name, phone, status, device } = req.body;

    if (!email || !status) {
        return res.status(400).json({ success: false, error: "Missing required fields" });
    }

    res.status(200).json({ success: true, message: `Security dispatch started.` });

    const isSuccess = status.toUpperCase() === "SUCCESS";
    const dateFormatted = new Date().toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'Africa/Addis_Ababa' });
    const timeFormatted = new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: true, timeZone: 'Africa/Addis_Ababa' });
    const fullDateTime = `${dateFormatted} • ${timeFormatted}`;

    // 🔍 AUTOMATIC PHONE NUMBER LOOKUP FROM FIREBASE
    let customerPhone = phone;
    if (!customerPhone || customerPhone === "Not Provided" || customerPhone === "N/A") {
        try {
            const usersSnap = await db.ref('users').once('value');
            usersSnap.forEach((child) => {
                const u = child.val();
                if (u && u.email && u.email.toLowerCase() === email.toLowerCase()) {
                    customerPhone = child.key || u.phone;
                }
            });
        } catch (e) {}
    }
    if (!customerPhone) customerPhone = "Available in Realtime Database";

    const resetLink = "https://bayra-backend-eu.onrender.com/reset-password";

    // 1️⃣ CUSTOMER EMAIL HTML
    const customerHtml = `
    <!DOCTYPE html>
    <html>
    <head><meta charset="utf-8"></head>
    <body style="margin: 0; padding: 20px 10px; background-color: #f4f6fb; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
        <table width="100%" cellpadding="0" cellspacing="0">
            <tr>
                <td align="center">
                    <table width="100%" style="max-width: 560px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #eef0f6;">
                        
                        <!-- 🟦 BLUE HEADER WITH EMBEDDED LOGO BADGE -->
                        <tr>
                            <td style="background-color: #1A237E; padding: 32px 25px; text-align: center;">
                                <div style="display: inline-block; width: 50px; height: 50px; background: white; border-radius: 50%; line-height: 50px; font-size: 26px; margin-bottom: 10px; box-shadow: 0 4px 10px rgba(0,0,0,0.2);">
                                    🚕
                                </div>
                                <h1 style="color: #ffffff; margin: 0; font-size: 24px; font-weight: 800; letter-spacing: 0.5px;">BAYRA TRAVEL</h1>
                                <p style="color: #c5cae9; margin: 6px 0 0 0; font-size: 13px;">Your journey starts here.</p>
                            </td>
                        </tr>

                        <!-- WHITE BODY -->
                        <tr>
                            <td style="padding: 35px 30px;">
                                <h2 style="color: #0f172a; margin-top: 0; font-size: 20px;">Welcome, ${name || 'Passenger'}! 👋</h2>
                                <p style="color: #475569; font-size: 15px; line-height: 1.6; margin: 0 0 10px 0;">
                                    We're happy to have you with <strong>Bayra Travel</strong>.
                                </p>
                                <p style="color: #475569; font-size: 15px; line-height: 1.6; margin: 0 0 25px 0;">
                                    Your account sign-in was successfully confirmed, and your Bayra Travel account is now ready for your next journey.
                                </p>

                                <!-- 🛡️ ELEGANT SECURITY CARD -->
                                <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 20px; margin-bottom: 25px;">
                                    <p style="margin: 0 0 14px 0; color: #166534; font-weight: 700; font-size: 14px;">
                                        🛡️ SIGN-IN VERIFIED
                                    </p>
                                    <p style="margin: 0 0 16px 0; color: #64748b; font-size: 13px;">Your account was successfully accessed</p>
                                    
                                    <table width="100%" style="font-size: 13px;">
                                        <tr>
                                            <td style="color: #64748b; padding: 4px 0;">Device</td>
                                            <td style="color: #0f172a; font-weight: 600; text-align: right; padding: 4px 0;">${device || 'Android Smartphone'}</td>
                                        </tr>
                                        <tr>
                                            <td style="color: #64748b; padding: 4px 0;">Date & Time</td>
                                            <td style="color: #0f172a; font-weight: 600; text-align: right; padding: 4px 0;">${fullDateTime}</td>
                                        </tr>
                                        <tr>
                                            <td style="color: #64748b; padding: 4px 0;">Location</td>
                                            <td style="color: #0f172a; font-weight: 600; text-align: right; padding: 4px 0;">Arba Minch, Ethiopia</td>
                                        </tr>
                                    </table>
                                </div>

                                <!-- 🔐 ACCOUNT PROTECTION SECTION -->
                                <div style="margin-bottom: 30px; text-align: center;">
                                    <p style="color: #0f172a; font-weight: 600; font-size: 14px; margin: 0 0 6px 0;">🔐 Your account is protected</p>
                                    <p style="color: #64748b; font-size: 13px; margin: 0 0 4px 0;">If this was you, no action is required.</p>
                                    <p style="color: #64748b; font-size: 13px; margin: 0 0 20px 0;">If you don't recognize this activity, please secure your account immediately.</p>
                                    
                                    <a href="${resetLink}" style="background-color: #D50000; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 10px; font-weight: 700; font-size: 14px; display: inline-block; box-shadow: 0 4px 12px rgba(213,0,0,0.25);">
                                        [ 🔒 SECURE MY ACCOUNT ]
                                    </a>
                                </div>

                                <!-- 🚕 RIDE PROMOTION CARD -->
                                <div style="background-color: #f1f5f9; border-radius: 12px; padding: 18px; text-align: center;">
                                    <p style="color: #1A237E; font-weight: 700; font-size: 14px; margin: 0 0 6px 0;">🚕 Ready for your next ride?</p>
                                    <p style="color: #475569; font-size: 13px; margin: 0 0 10px 0;">
                                        Whether you're heading across town or planning your next trip, Bayra Travel is here to move you forward.
                                    </p>
                                    <p style="color: #1A237E; font-weight: 700; font-size: 12px; margin: 0;">
                                        Safe • Reliable • Convenient
                                    </p>
                                </div>

                            </td>
                        </tr>

                        <!-- FOOTER -->
                        <tr>
                            <td style="background-color: #f8fafc; padding: 25px 30px; text-align: center; border-top: 1px solid #e2e8f0;">
                                <p style="font-size: 13px; font-weight: 600; color: #475569; margin: 0 0 4px 0;">Thank you for choosing Bayra Travel.</p>
                                <p style="font-size: 12px; color: #64748b; margin: 0 0 10px 0;">Southern Ethiopia's trusted ride platform</p>
                                <p style="font-size: 12px; font-weight: 600; color: #1A237E; margin: 0 0 4px 0;">Bayra Travel Team</p>
                                <p style="font-size: 11px; color: #94a3b8; margin: 0 0 12px 0;">📍 Arba Minch, Ethiopia</p>
                                <p style="font-size: 11px; color: #cbd5e1; margin: 0;">This is an automated security notification. Please do not reply to this email.</p>
                            </td>
                        </tr>

                    </table>
                </td>
            </tr>
        </table>
    </body>
    </html>
    `;

    // 2️⃣ DIRECTOR EXECUTIVE BRIEFING HTML
    const directorSubject = isSuccess
        ? `📈 [DIRECTOR REPORT] Login Success: ${name} (${customerPhone})`
        : `🚨 [URGENT ACTION] Customer Login Failed: ${name} (${customerPhone})`;

    const directorHtml = `
        <div style="font-family: Arial, sans-serif; max-width: 580px; margin: auto; border: 2px solid ${isSuccess ? '#1A237E' : '#D50000'}; border-radius: 16px; overflow: hidden; background: #ffffff;">
            <div style="background-color: ${isSuccess ? '#1A237E' : '#D50000'}; padding: 22px 25px; color: #ffffff;">
                <h2 style="margin: 0; font-size: 20px;">👑 BAYRA EXECUTIVE DISPATCH</h2>
                <p style="margin: 4px 0 0 0; font-size: 12px; color: rgba(255,255,255,0.85);">MANAGEMENT & CUSTOMER RETENTION DASHBOARD</p>
            </div>
            <div style="padding: 25px;">
                <div style="background-color: ${isSuccess ? '#e8f5e9' : '#ffebee'}; border-radius: 8px; padding: 12px 16px; margin-bottom: 20px;">
                    <p style="margin: 0; color: ${isSuccess ? '#2e7d32' : '#c62828'}; font-weight: bold; font-size: 14px;">
                        ${isSuccess ? '✅ Active Passenger Session Confirmed' : '🛑 FAILED LOGIN — PASSENGER UNABLE TO ACCESS ACCOUNT'}
                    </p>
                </div>

                <table width="100%" style="font-size: 14px; border-collapse: collapse;">
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Passenger Name:</strong></td>
                        <td style="padding: 10px 0; color: #0f172a; font-weight: bold;">${name || 'Anonymous'}</td>
                    </tr>
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Phone Number:</strong></td>
                        <td style="padding: 10px 0; color: #1A237E; font-weight: 800; font-size: 16px;">
                            <a href="tel:${customerPhone}" style="color: #1A237E; text-decoration: underline;">${customerPhone}</a>
                        </td>
                    </tr>
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Customer Email:</strong></td>
                        <td style="padding: 10px 0; color: #0f172a;">${email}</td>
                    </tr>
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Device:</strong></td>
                        <td style="padding: 10px 0; color: #0f172a;">${device || 'Android'}</td>
                    </tr>
                    <tr>
                        <td style="padding: 10px 0; color: #64748b;"><strong>Time (EAT):</strong></td>
                        <td style="padding: 10px 0; color: #0f172a;">${fullDateTime}</td>
                    </tr>
                </table>

                <div style="margin-top: 25px; text-align: center; background: #f8fafc; padding: 18px; border-radius: 12px; border: 1px dashed #cbd5e1;">
                    <p style="margin: 0 0 12px 0; color: #475569; font-size: 13px; font-weight: 600;">
                        ${isSuccess ? 'Customer is active. You can contact them directly:' : '⚠️ Customer is stuck on login. Call them immediately to assist:'}
                    </p>
                    <a href="tel:${customerPhone}" style="background-color: ${isSuccess ? '#1A237E' : '#D50000'}; color: #ffffff; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: bold; display: inline-block;">
                        📞 CALL CUSTOMER (${customerPhone})
                    </a>
                </div>
            </div>
            <div style="background: #f8fafc; padding: 12px 25px; font-size: 11px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0;">
                Bayra Travel Operations Control Tower • Confidential
            </div>
        </div>
    `;

    // 🚀 EXECUTE SEQUENTIALLY TO PREVENT DROPPED EMAILS
    try {
        // Step A: Send to Passenger
        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Travel Security", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: email, name: name || "Passenger" }],
            subject: isSuccess ? "🛡️ Welcome to Bayra Travel — Your Account Is Secure" : "⚠️ Urgent: Failed Password Attempt on Bayra Travel",
            htmlContent: customerHtml
        }, { headers: { 'api-key': process.env.BREVO_API_KEY, 'Content-Type': 'application/json' } });

        console.log(`✅ [1/2 CUSTOMER EMAIL DELIVERED] Successfully delivered to ${email}`);

        // Small 300ms pause to ensure Brevo processes cleanly
        await new Promise(r => setTimeout(r, 300));

        // Step B: Send to Director (With Phone Number!)
        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Control Tower", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: "bayratraveldonotreplay@gmail.com", name: "Executive Director" }],
            subject: directorSubject,
            htmlContent: directorHtml
        }, { headers: { 'api-key': process.env.BREVO_API_KEY, 'Content-Type': 'application/json' } });

        console.log(`✅ [2/2 DIRECTOR BRIEFING DELIVERED] Sent to Director with Phone: ${customerPhone}!`);

    } catch (err) {
        console.error("❌ [DISPATCH ERROR]:", err.response ? err.response.data : err.message);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });
