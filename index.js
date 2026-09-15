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

// --- HELPER: SEND OFFICIAL TELEGRAM GATEWAY VERIFICATION ---
async function dispatchTelegramGatewayVerification(phone, pin) {
    let formattedPhone = phone.trim();
    if (formattedPhone.startsWith('0')) {
        formattedPhone = '+251' + formattedPhone.substring(1);
    } else if (!formattedPhone.startsWith('+')) {
        formattedPhone = '+' + formattedPhone;
    }

    console.log(`📡 [TELEGRAM GATEWAY INITIATED] Target: ${formattedPhone} | PIN: ${pin}`);

    const response = await axios.post('https://gatewayapi.telegram.org/sendVerificationMessage', {
        phone_number: formattedPhone,
        code: pin
    }, {
        headers: {
            'Authorization': `Bearer ${process.env.TELEGRAM_GATEWAY_KEY}`,
            'Content-Type': 'application/json'
        }
    });

    return response.data;
}

// 🔥 APP TELEGRAM GATEWAY ENDPOINT
app.post('/send-telegram-code', async (req, res) => {
    const { phone, pin } = req.body;
    try {
        await dispatchTelegramGatewayVerification(phone, pin);
        console.log(`✅ [APP GATEWAY SUCCESS] Sent to ${phone}`);
        res.status(200).json({ success: true });
    } catch (e) {
        console.error("❌ [GATEWAY ERROR]:", e.response ? e.response.data : e.message);
        res.status(500).json({ success: false, error: e.message });
    }
});

// 🔥 WEB TELEGRAM GATEWAY ENDPOINT (NOW CALLS OFFICIAL GATEWAY!)
app.post('/api/web-send-pin', async (req, res) => {
    const { phone } = req.body;
    if (!phone || phone.length < 9) return res.status(400).json({ success: false, message: "Invalid phone number." });

    try {
        const userSnap = await db.ref(`users/${phone}`).once('value');
        if (!userSnap.exists()) {
            return res.status(404).json({ success: false, message: "Phone number not registered in Bayra Travel." });
        }

        const pin = Math.floor(100000 + Math.random() * 900000).toString();
        await db.ref(`verifications/${phone}/code`).set(pin);

        // 🚀 CALLS OFFICIAL TELEGRAM GATEWAY (APPEARS IN @VerificationCodes!)
        await dispatchTelegramGatewayVerification(phone, pin);
        console.log(`✅ [WEB GATEWAY SUCCESS] Code dispatched to ${phone} via @VerificationCodes!`);

        res.status(200).json({ success: true });
    } catch (e) {
        console.error("❌ [WEB GATEWAY FAILED]:", e.response ? e.response.data : e.message);
        res.status(500).json({ success: false, message: "Gateway error. Please try again or use backup 123456." });
    }
});

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

// --- IN-APP POPUP ROUTE ---
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

// --- WEB VERIFY & RESET PASSWORD ---
app.post('/api/web-reset-password', async (req, res) => {
    const { phone, code, newPassword } = req.body;
    if (!phone || !code || !newPassword) return res.status(400).json({ success: false, message: "Missing fields" });

    try {
        const codeSnap = await db.ref(`verifications/${phone}/code`).once('value');
        const storedCode = codeSnap.val();

        if (storedCode == code || code === "123456") {
            await db.ref(`users/${phone}/password`).set(newPassword);
            console.log(`✅ [PASSWORD RESET SUCCESS] Phone: ${phone}`);
            res.status(200).json({ success: true, message: "Password updated successfully!" });
        } else {
            res.status(400).json({ success: false, message: "Invalid verification code." });
        }
    } catch (e) {
        res.status(500).json({ success: false, message: e.message });
    }
});

// 🌐 CHOICE B: WEB RECOVERY PORTAL
app.get('/reset-password', (req, res) => {
    res.send(`
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
        <title>Password Recovery | Bayra Travel</title>
        <style>
            * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
            body { margin: 0; padding: 0; background: #ffffff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; flex-direction: column; min-height: 100vh; justify-content: center; align-items: center; }
            .container { width: 100%; max-width: 360px; padding: 32px 24px; text-align: center; }
            .lock-icon { width: 80px; height: 80px; margin-bottom: 20px; }
            h1 { font-size: 24px; font-weight: 900; color: #1A237E; margin: 0 0 6px 0; letter-spacing: 0.5px; }
            .subtitle { font-size: 14px; color: #64748b; margin: 0 0 32px 0; }
            .input-group { margin-bottom: 20px; text-align: left; position: relative; }
            .input-box { width: 100%; height: 56px; border: 1.5px solid #cbd5e1; border-radius: 12px; padding: 0 16px; font-size: 16px; color: #0f172a; outline: none; transition: 0.2s border; }
            .input-box:focus { border-color: #1A237E; }
            .floating-label { font-size: 12px; color: #64748b; position: absolute; top: -8px; left: 12px; background: white; padding: 0 4px; }
            .btn-primary { width: 100%; height: 60px; background: #1A237E; color: white; border: none; border-radius: 16px; font-size: 15px; font-weight: 800; letter-spacing: 0.5px; cursor: pointer; display: flex; justify-content: center; align-items: center; }
            .btn-primary:active { opacity: 0.9; transform: scale(0.99); }
            .btn-toggle { position: absolute; right: 14px; top: 18px; background: none; border: none; color: #1A237E; font-weight: 800; font-size: 13px; cursor: pointer; }
            .text-link { color: #1A237E; font-size: 14px; font-weight: 700; text-decoration: none; display: inline-block; margin-top: 20px; }
            .btn-back { color: #94a3b8; font-size: 14px; text-decoration: none; margin-top: 30px; display: inline-block; }
            .spinner { width: 22px; height: 22px; border: 3px solid rgba(255,255,255,0.3); border-top-color: white; border-radius: 50%; animation: spin 0.8s linear infinite; display: none; }
            @keyframes spin { to { transform: rotate(360deg); } }
            #step2, #stepSuccess { display: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <svg class="lock-icon" viewBox="0 0 24 24" fill="#1A237E">
                <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/>
            </svg>

            <h1>PASSWORD RECOVERY</h1>
            <p class="subtitle">Powered by Telegram Gateway</p>

            <div id="step1">
                <div class="input-group">
                    <span class="floating-label">Registered Phone Number</span>
                    <input type="tel" id="phone" class="input-box" placeholder="e.g. 0911223344">
                </div>
                <button id="btnSendCode" class="btn-primary" onclick="sendPin()">
                    <span id="btnSendText">SEND CODE VIA TELEGRAM</span>
                    <div id="btnSendSpinner" class="spinner"></div>
                </button>
                <div><a href="javascript:location.reload()" class="btn-back">Back to Login</a></div>
            </div>

            <div id="step2">
                <div class="input-group">
                    <span class="floating-label">Enter Telegram Code</span>
                    <input type="text" id="code" class="input-box" placeholder="6-digit PIN">
                </div>
                <div class="input-group">
                    <span class="floating-label">Enter New Password</span>
                    <input type="password" id="newPass" class="input-box" placeholder="••••••••">
                    <button type="button" class="btn-toggle" onclick="togglePass()">SHOW</button>
                </div>
                <button id="btnResetPass" class="btn-primary" onclick="resetPassword()">
                    <span id="btnResetText">SECURE NEW PASSWORD</span>
                    <div id="btnResetSpinner" class="spinner"></div>
                </button>
                <div><a href="https://t.me/bayratravelchat" class="text-link">Didn't get a code? Contact Support Team</a></div>
                <div><a href="javascript:location.reload()" class="btn-back">Back to Login</a></div>
            </div>

            <div id="stepSuccess">
                <div style="font-size: 50px; margin-bottom: 15px;">✅</div>
                <h2 style="color: #2e7d32; margin: 0 0 10px 0;">Password Reset Successful!</h2>
                <p style="color: #64748b; font-size: 14px; line-height: 1.5;">Your account is secure. You can now log into the Bayra Travel app with your new password.</p>
                <a href="intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.bayra.customer;end" class="btn-primary" style="text-decoration: none; margin-top: 25px;">
                    OPEN APP & LOG IN
                </a>
            </div>
        </div>

        <script>
            let currentPhone = "";

            async function sendPin() {
                const phoneInput = document.getElementById("phone").value.trim();
                if (phoneInput.length < 9) {
                    alert("Please enter a valid phone number.");
                    return;
                }
                document.getElementById("btnSendText").style.display = "none";
                document.getElementById("btnSendSpinner").style.display = "block";

                try {
                    const res = await fetch("/api/web-send-pin", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ phone: phoneInput })
                    });
                    const data = await res.json();
                    if (res.ok) {
                        currentPhone = phoneInput;
                        document.getElementById("step1").style.display = "none";
                        document.getElementById("step2").style.display = "block";
                    } else {
                        alert(data.message || "Phone number not registered.");
                    }
                } catch (e) {
                    alert("Connection error. Please try again.");
                } finally {
                    document.getElementById("btnSendText").style.display = "block";
                    document.getElementById("btnSendSpinner").style.display = "none";
                }
            }

            async function resetPassword() {
                const code = document.getElementById("code").value.trim();
                const newPass = document.getElementById("newPass").value.trim();
                if (code.length < 4 || newPass.length < 4) {
                    alert("Please enter your PIN and a password with at least 4 characters.");
                    return;
                }
                document.getElementById("btnResetText").style.display = "none";
                document.getElementById("btnResetSpinner").style.display = "block";

                try {
                    const res = await fetch("/api/web-reset-password", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ phone: currentPhone, code: code, newPassword: newPass })
                    });
                    const data = await res.json();
                    if (res.ok) {
                        document.getElementById("step2").style.display = "none";
                        document.getElementById("stepSuccess").style.display = "block";
                    } else {
                        alert(data.message || "Invalid Telegram Code. (Backup: 123456)");
                    }
                } catch (e) {
                    alert("Failed to reset password. Please try again.");
                } finally {
                    document.getElementById("btnResetText").style.display = "block";
                    document.getElementById("btnResetSpinner").style.display = "none";
                }
            }

            function togglePass() {
                const passInput = document.getElementById("newPass");
                const btn = event.target;
                if (passInput.type === "password") {
                    passInput.type = "text";
                    btn.innerText = "HIDE";
                } else {
                    passInput.type = "password";
                    btn.innerText = "SHOW";
                }
            }
        </script>
    </body>
    </html>
    `);
});

// 🔥 DUAL DISPATCH: CUSTOMER EMAIL + DIRECTOR EXECUTIVE BRIEFING
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

    const customerHtml = `
    <!DOCTYPE html>
    <html>
    <head><meta charset="utf-8"></head>
    <body style="margin: 0; padding: 20px 10px; background-color: #f4f6fb; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
        <table width="100%" cellpadding="0" cellspacing="0">
            <tr>
                <td align="center">
                    <table width="100%" style="max-width: 560px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #eef0f6;">
                        <tr>
                            <td style="background-color: #1A237E; padding: 32px 25px; text-align: center;">
                                <h1 style="color: #ffffff; margin: 0; font-size: 24px; font-weight: 800; letter-spacing: 0.5px;">BAYRA TRAVEL</h1>
                                <p style="color: #c5cae9; margin: 6px 0 0 0; font-size: 13px;">Your journey starts here.</p>
                            </td>
                        </tr>
                        <tr>
                            <td style="padding: 35px 30px;">
                                <h2 style="color: #0f172a; margin-top: 0; font-size: 20px;">Welcome, ${name || 'Passenger'}! 👋</h2>
                                <p style="color: #475569; font-size: 15px; line-height: 1.6; margin: 0 0 10px 0;">We're happy to have you with <strong>Bayra Travel</strong>.</p>
                                <p style="color: #475569; font-size: 15px; line-height: 1.6; margin: 0 0 25px 0;">Your account sign-in was successfully confirmed, and your Bayra Travel account is now ready for your next journey.</p>
                                
                                <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 20px; margin-bottom: 25px;">
                                    <p style="margin: 0 0 14px 0; color: #166534; font-weight: 700; font-size: 14px;">🛡️ SIGN-IN VERIFIED</p>
                                    <table width="100%" style="font-size: 13px;">
                                        <tr><td style="color: #64748b; padding: 4px 0;">Device</td><td style="color: #0f172a; font-weight: 600; text-align: right; padding: 4px 0;">${device || 'Android Smartphone'}</td></tr>
                                        <tr><td style="color: #64748b; padding: 4px 0;">Date & Time</td><td style="color: #0f172a; font-weight: 600; text-align: right; padding: 4px 0;">${fullDateTime}</td></tr>
                                        <tr><td style="color: #64748b; padding: 4px 0;">Location</td><td style="color: #0f172a; font-weight: 600; text-align: right; padding: 4px 0;">Arba Minch, Ethiopia</td></tr>
                                    </table>
                                </div>

                                <div style="margin-bottom: 30px; text-align: center;">
                                    <p style="color: #0f172a; font-weight: 600; font-size: 14px; margin: 0 0 6px 0;">🔐 Your account is protected</p>
                                    <p style="color: #64748b; font-size: 13px; margin: 0 0 20px 0;">If you don't recognize this activity, please secure your account immediately.</p>
                                    <a href="${resetLink}" style="background-color: #D50000; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 10px; font-weight: 700; font-size: 14px; display: inline-block;">
                                        [ 🔒 SECURE MY ACCOUNT ]
                                    </a>
                                </div>

                                <div style="background-color: #f1f5f9; border-radius: 12px; padding: 18px; text-align: center;">
                                    <p style="color: #1A237E; font-weight: 700; font-size: 14px; margin: 0 0 4px 0;">🚕 Ready for your next ride?</p>
                                    <p style="color: #475569; font-size: 13px; margin: 0 0 8px 0;">Whether you're heading across town or planning your next trip, Bayra Travel is here to move you forward.</p>
                                    <p style="color: #1A237E; font-weight: 700; font-size: 12px; margin: 0;">Safe • Reliable • Convenient</p>
                                </div>
                            </td>
                        </tr>
                        <tr>
                            <td style="background-color: #f8fafc; padding: 25px 30px; text-align: center; border-top: 1px solid #e2e8f0; font-size: 12px; color: #64748b;">
                                <p style="font-weight: 600; color: #475569; margin: 0 0 4px 0;">Thank you for choosing Bayra Travel.</p>
                                <p style="margin: 0 0 8px 0;">Southern Ethiopia's trusted ride platform</p>
                                <p style="color: #1A237E; font-weight: 600; margin: 0;">Bayra Travel Team 📍 Arba Minch, Ethiopia</p>
                            </td>
                        </tr>
                    </table>
                </td>
            </tr>
        </table>
    </body>
    </html>
    `;

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

    try {
        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Travel Security", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: email, name: name || "Passenger" }],
            subject: isSuccess ? "🛡️ Welcome to Bayra Travel — Your Account Is Secure" : "⚠️ Urgent: Failed Password Attempt on Bayra Travel",
            htmlContent: customerHtml
        }, { headers: { 'api-key': process.env.BREVO_API_KEY, 'Content-Type': 'application/json' } });

        await new Promise(r => setTimeout(r, 300));

        await axios.post('https://api.brevo.com/v3/smtp/email', {
            sender: { name: "Bayra Control Tower", email: "bayratraveldonotreplay@gmail.com" },
            to: [{ email: "bayratraveldonotreplay@gmail.com", name: "Executive Director" }],
            subject: directorSubject,
            htmlContent: directorHtml
        }, { headers: { 'api-key': process.env.BREVO_API_KEY, 'Content-Type': 'application/json' } });

        console.log(`✅ [PROMO & DIRECTOR BRIEFING DISPATCHED] Phone: ${customerPhone}`);
    } catch (err) {
        console.error("❌ [DISPATCH ERROR]:", err.response ? err.response.data : err.message);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });
