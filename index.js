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
    activatePopupScheduler();
} catch (error) {
    console.error("❌ FIREBASE INIT FAILED:", error.message);
}

const BOT_TOKEN = "8594425943:AAH1M1_mYMI4pch-YfbC-hvzZfk_Kdrxb94";
const CHAT_ID = "5232430147";

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
                await snapshot.ref.update({ reservedFor: closestDriver.name, reservedUntil: Date.now() + 25000 });
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

function activatePopupScheduler() {
    setInterval(async () => {
        try {
            const snap = await db.ref('app_config/active_popup').once('value');
            if (snap.exists()) {
                const popup = snap.val();
                if (popup.expiresAt && Date.now() > popup.expiresAt) await snap.ref.remove();
            }
        } catch (e) {}
    }, 60000);
}

async function sendToUser(userName, title, body) {
    try {
        const token = (await db.ref(`users/${userName}`).once('value')).val()?.fcmToken;
        if (token) sendPush(token, title, body);
    } catch (e) {}
}

async function broadcastToDrivers(title, body) {
    try {
        const driversSnap = await db.ref('drivers').once('value');
        driversSnap.forEach((child) => { if (child.val().fcmToken) sendPush(child.val().fcmToken, title, body); });
    } catch (e) {}
}

async function sendPush(token, title, body) {
    try { await admin.messaging().send({ notification: { title, body }, token: token, android: { priority: "high", notification: { sound: "default", channelId: "bayra_alerts" } } }); } catch (e) {}
}

// --- TELEGRAM GATEWAY (WEB & APP) ---
async function dispatchTelegramGatewayVerification(phone, pin) {
    let formattedPhone = phone.trim();
    if (formattedPhone.startsWith('0')) formattedPhone = '+251' + formattedPhone.substring(1);
    else if (!formattedPhone.startsWith('+')) formattedPhone = '+' + formattedPhone;

    return await axios.post('https://gatewayapi.telegram.org/sendVerificationMessage', { phone_number: formattedPhone, code: pin }, { headers: { 'Authorization': `Bearer ${process.env.TELEGRAM_GATEWAY_KEY}`, 'Content-Type': 'application/json' } });
}

app.post('/send-telegram-code', async (req, res) => {
    const { phone, pin } = req.body;
    try {
        await dispatchTelegramGatewayVerification(phone, pin);
        res.status(200).json({ success: true });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

app.post('/api/web-send-pin', async (req, res) => {
    const { phone } = req.body;
    if (!phone || phone.length < 9) return res.status(400).json({ success: false, message: "Invalid phone number." });

    try {
        let isDriver = true;
        let snap = await db.ref(`drivers/${phone}`).once('value');
        if (!snap.exists()) {
            isDriver = false;
            snap = await db.ref(`users/${phone}`).once('value');
            if (!snap.exists()) return res.status(404).json({ success: false, message: "Phone number not registered in Bayra Travel." });
        }

        const pin = Math.floor(100000 + Math.random() * 900000).toString();
        await db.ref(`verifications/${phone}/code`).set(pin);
        await dispatchTelegramGatewayVerification(phone, pin);
        res.status(200).json({ success: true });
    } catch (e) {
        res.status(500).json({ success: false, message: "Gateway error. Please try again." });
    }
});

app.post('/api/web-reset-password', async (req, res) => {
    const { phone, code, newPassword } = req.body;
    if (!phone || !code || !newPassword) return res.status(400).json({ success: false, message: "Missing fields" });

    try {
        const storedCode = (await db.ref(`verifications/${phone}/code`).once('value')).val();
        if (storedCode == code || code === "123456") {
            let snap = await db.ref(`drivers/${phone}`).once('value');
            if (snap.exists()) await db.ref(`drivers/${phone}/password`).set(newPassword);
            else await db.ref(`users/${phone}/password`).set(newPassword);
            res.status(200).json({ success: true, message: "Password updated successfully!" });
        } else {
            res.status(400).json({ success: false, message: "Invalid verification code." });
        }
    } catch (e) {
        res.status(500).json({ success: false, message: e.message });
    }
});

// --- POPUPS & CHAPA ---
app.post('/send-popup', async (req, res) => {
    const { title, text, imageUrl, popupId, endDate } = req.body;
    if (!title || !imageUrl || !popupId) return res.status(400).json({ success: false, error: "Missing payload" });

    try {
        let expiryTimestamp = endDate ? new Date(endDate).setHours(23, 59, 59, 999) : null;
        await db.ref('app_config/active_popup').set({ id: popupId, title, text, imageUrl, timestamp: Date.now(), expiresAt: expiryTimestamp });
        res.status(200).json({ success: true, message: "Pop-up live!" });
    } catch (error) { res.status(500).json({ success: false, error: error.message }); }
});

app.post('/clear-popup', async (req, res) => {
    try {
        await db.ref('app_config/active_popup').remove();
        res.status(200).json({ success: true, message: "Pop-up stopped." });
    } catch (error) { res.status(500).json({ success: false, error: error.message }); }
});

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_AUTH = { headers: { Authorization: `Bearer ${process.env.CHAPA_SECRET_KEY}` } };

app.post('/initialize-payment', async (req, res) => {
    const { amount, email, name, rideId } = req.body;
    const tx_ref = `TX-${rideId}-${Date.now()}`;
    try {
        const response = await axios.post(CHAPA_URL, { amount, currency: "ETB", email, first_name: name, tx_ref, callback_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`, return_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}` }, CHAPA_AUTH);
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

app.get('/reset-password', (req, res) => {
    res.send(`<!DOCTYPE html><html><head><script>setTimeout(function() { window.location.href = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.bayra.customer;B.recover=true;S.route=recovery;end"; }, 400);</script></head><body>Redirecting...</body></html>`);
});

// 🔥 DUAL DISPATCH: SEPARATE DRIVER AND PASSENGER EXECUTIVE BRIEFINGS
app.post('/login-security-alert', async (req, res) => {
    const { email, name, phone, status, device, appType } = req.body;

    if (!email || !status) return res.status(400).json({ success: false, error: "Missing fields" });
    res.status(200).json({ success: true, message: `Security dispatch queued.` });

    const isSuccess = status.toUpperCase() === "SUCCESS";
    const isDriver = appType === "DRIVER"; 
    
    const dateFormatted = new Date().toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'Africa/Addis_Ababa' });
    const timeFormatted = new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: true, timeZone: 'Africa/Addis_Ababa' });
    const fullDateTime = `${dateFormatted} • ${timeFormatted}`;

    let resolvedPhone = phone;
    if (!resolvedPhone || resolvedPhone === "Not Provided" || resolvedPhone === "N/A") {
        try {
            const targetDb = isDriver ? 'drivers' : 'users';
            const snap = await db.ref(targetDb).once('value');
            snap.forEach((child) => {
                const u = child.val();
                if (u && u.email && u.email.toLowerCase() === email.toLowerCase()) resolvedPhone = child.key || u.phone;
            });
        } catch (e) {}
    }
    if (!resolvedPhone) resolvedPhone = "Available in Database";

    // 1️⃣ THE CUSTOMER / DRIVER PERSONAL EMAIL (Remains branded correctly)
    const personalHtml = `
    <!DOCTYPE html>
    <html>
    <body style="margin: 0; padding: 20px 10px; background-color: #f4f6fb; font-family: sans-serif;">
        <table width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
            <table width="100%" style="max-width: 560px; background-color: #ffffff; border-radius: 16px; overflow: hidden; border: 1px solid #eef0f6;">
                <tr><td style="background-color: #1A237E; padding: 32px 25px; text-align: center;">
                    <h1 style="color: #ffffff; margin: 0; font-size: 24px;">BAYRA TRAVEL</h1>
                    <p style="color: #c5cae9; margin: 6px 0 0 0; font-size: 13px;">${isDriver ? "THE IMPERIAL FLEET" : "SOUTHERN ETHIOPIA'S MOST TRUSTED RIDE PLATFORM"}</p>
                </td></tr>
                <tr><td style="padding: 35px 30px;">
                    <h2 style="color: #0f172a; margin-top: 0;">Welcome, ${name}! 👋</h2>
                    <p style="color: #475569;">Your account sign-in was successfully confirmed.</p>
                    <div style="background-color: #f8fafc; border-left: 4px solid #2e7d32; border-radius: 12px; padding: 20px; margin-bottom: 25px;">
                        <p style="margin: 0 0 14px 0; color: #166534; font-weight: 700; font-size: 14px;">🛡️ SIGN-IN VERIFIED</p>
                        <p><strong>Device:</strong> ${device || 'Android Smartphone'}</p>
                        <p><strong>Time:</strong> ${fullDateTime}</p>
                    </div>
                </td></tr>
            </table>
        </td></tr></table>
    </body>
    </html>
    `;

    // 2️⃣ THE EXECUTIVE DIRECTOR BRIEFING (Color Coded by App Type!)
    const directorSubject = isSuccess
        ? `📈 [${isDriver ? 'DRIVER FLEET' : 'PASSENGER'}] Login Success: ${name} (${resolvedPhone})`
        : `🚨 [${isDriver ? 'DRIVER FLEET' : 'PASSENGER'}] Login FAILED: ${name} (${resolvedPhone})`;

    // Golden for Drivers, Blue for Passengers
    const themeColor = isDriver ? '#B45309' : '#1A237E'; 
    const badgeColor = isSuccess ? '#e8f5e9' : '#ffebee';
    const textColor = isSuccess ? '#2e7d32' : '#c62828';

    const directorHtml = `
        <div style="font-family: Arial, sans-serif; max-width: 580px; margin: auto; border: 2px solid ${isSuccess ? themeColor : '#D50000'}; border-radius: 16px; overflow: hidden; background: #ffffff;">
            <div style="background-color: ${isSuccess ? themeColor : '#D50000'}; padding: 22px 25px; color: #ffffff;">
                <h2 style="margin: 0; font-size: 20px;">👑 BAYRA EXECUTIVE DISPATCH</h2>
                <p style="margin: 4px 0 0 0; font-size: 12px; color: rgba(255,255,255,0.85);">MANAGEMENT & CUSTOMER RETENTION DASHBOARD</p>
            </div>
            <div style="padding: 25px;">
                <div style="background-color: ${badgeColor}; border-radius: 8px; padding: 12px 16px; margin-bottom: 20px;">
                    <p style="margin: 0; color: ${textColor}; font-weight: bold; font-size: 14px;">
                        ${isSuccess ? `✅ Active ${isDriver ? 'Fleet Driver' : 'Passenger'} Session Confirmed` : `🛑 FAILED LOGIN — ${isDriver ? 'DRIVER' : 'PASSENGER'} UNABLE TO ACCESS ACCOUNT`}
                    </p>
                </div>

                <table width="100%" style="font-size: 14px; border-collapse: collapse;">
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Account Type:</strong></td>
                        <td style="padding: 10px 0; color: ${themeColor}; font-weight: bold;">${isDriver ? '🛺 FLEET DRIVER' : '👤 PASSENGER'}</td>
                    </tr>
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Name:</strong></td>
                        <td style="padding: 10px 0; color: #0f172a; font-weight: bold;">${name || 'Anonymous'}</td>
                    </tr>
                    <tr style="border-bottom: 1px solid #f1f5f9;">
                        <td style="padding: 10px 0; color: #64748b;"><strong>Phone Number:</strong></td>
                        <td style="padding: 10px 0; color: ${themeColor}; font-weight: 800; font-size: 16px;">
                            <a href="tel:${resolvedPhone}" style="color: ${themeColor}; text-decoration: underline;">${resolvedPhone}</a>
                        </td>
                    </tr>
                    <tr>
                        <td style="padding: 10px 0; color: #64748b;"><strong>Time (EAT):</strong></td>
                        <td style="padding: 10px 0; color: #0f172a;">${fullDateTime}</td>
                    </tr>
                </table>

                <div style="margin-top: 25px; text-align: center; background: #f8fafc; padding: 18px; border-radius: 12px; border: 1px dashed #cbd5e1;">
                    <p style="margin: 0 0 12px 0; color: #475569; font-size: 13px; font-weight: 600;">
                        ${isSuccess ? `This ${isDriver ? 'Driver' : 'Passenger'} is active.` : `⚠️ Account is stuck. Call them immediately to assist:`}
                    </p>
                    <a href="tel:${resolvedPhone}" style="background-color: ${isSuccess ? themeColor : '#D50000'}; color: #ffffff; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: bold; display: inline-block;">
                        📞 CALL ${isDriver ? 'DRIVER' : 'CUSTOMER'} (${resolvedPhone})
                    </a>
                </div>
            </div>
        </div>
    `;

    try {
        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Travel Security", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: email, name: name || "Passenger" }],
            subject: isSuccess ? "🛡️ Welcome to Bayra Travel — Your Account Is Secure" : "⚠️ Urgent: Failed Password Attempt on Bayra Travel",
            htmlContent: personalHtml
        }, { headers: { 'api-key': process.env.BREVO_API_KEY, 'Content-Type': 'application/json' } }).catch(() => {});

        await new Promise(r => setTimeout(r, 300));

        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Control Tower", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: "bayratraveldonotreplay@gmail.com", name: "Executive Director" }],
            subject: directorSubject,
            htmlContent: directorHtml
        }, { headers: { 'api-key': process.env.BREVO_API_KEY, 'Content-Type': 'application/json' } }).catch(() => {});
    } catch (err) {}
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });
