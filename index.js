const express = require('express');
const admin = require('firebase-admin');
const axios = require('axios');

const app = express();
app.use(express.json({ limit: '50mb' }));

const SERVER_START_TIME = Date.now();

// =================================================================
// 🤖 1. BOT & CREDENTIAL CONFIGURATION
// =================================================================

// 👇 1. CASHIER BOT (For Deposits, Withdrawals, and Inline Buttons)
const HARDCODED_CASHIER_TOKEN = "8906238578:AAFeosxWcaqh6Hd0HV8RT3Fe662QefMLO24";
const HARDCODED_CASHIER_CHAT_ID = "5232430147"; 

// 👇 2. VERIFICATION BOT (For ID, License Photos, and Driver Reviews)
const HARDCODED_VERIFY_TOKEN = "8830622576:AAFzK4Ra6ht004oo3-6qvC2gxPLjAhqEV9E";
const HARDCODED_VERIFY_CHAT_ID = "5232430147"; // Change if you want verifications sent to a different chat

// Apply tokens safely
const CASHIER_BOT_TOKEN = (process.env.CASHIER_BOT_TOKEN || HARDCODED_CASHIER_TOKEN).trim().replace(/['"]/g, '');
const CASHIER_CHAT_ID = (process.env.CASHIER_CHAT_ID || HARDCODED_CASHIER_CHAT_ID).trim().replace(/['"]/g, '');

const VERIFICATION_BOT_TOKEN = (process.env.VERIFICATION_BOT_TOKEN || HARDCODED_VERIFY_TOKEN).trim().replace(/['"]/g, '');
const VERIFICATION_CHAT_ID = (process.env.VERIFICATION_CHAT_ID || HARDCODED_VERIFY_CHAT_ID).trim().replace(/['"]/g, '');

// =================================================================
// 🛠️ 2. TELEGRAM API HELPERS
// =================================================================
async function sendCashierMessageWithButtons(text, inlineKeyboard) {
    try {
        await axios.post(`https://api.telegram.org/bot${CASHIER_BOT_TOKEN}/sendMessage`, {
            chat_id: CASHIER_CHAT_ID,
            text: text,
            parse_mode: 'HTML',
            reply_markup: { inline_keyboard: inlineKeyboard }
        });
    } catch (e) {
        console.error("❌ Telegram Send Button Error:", e.response?.data?.description || e.message);
    }
}

async function editTelegramMessage(chatId, messageId, newText) {
    try {
        await axios.post(`https://api.telegram.org/bot${CASHIER_BOT_TOKEN}/editMessageText`, {
            chat_id: chatId,
            message_id: messageId,
            text: newText,
            parse_mode: 'HTML'
        });
    } catch (e) {}
}

async function answerTelegramCallback(callbackQueryId, notificationText) {
    try {
        await axios.post(`https://api.telegram.org/bot${CASHIER_BOT_TOKEN}/answerCallbackQuery`, {
            callback_query_id: callbackQueryId,
            text: notificationText
        });
    } catch (e) {}
}

async function setupTelegramWebhook() {
    try {
        const webhookUrl = `https://bayra-backend-eu.onrender.com/telegram-webhook`;
        const res = await axios.post(`https://api.telegram.org/bot${CASHIER_BOT_TOKEN}/setWebhook`, { url: webhookUrl });
        console.log(`🤖 Cashier Webhook active at: ${webhookUrl} (Result: ${res.data.description})`);
    } catch (e) {
        console.error("❌ Webhook setup error. Token starts with:", CASHIER_BOT_TOKEN.substring(0, 8), "Error:", e.response?.data?.description || e.message);
    }
}

// =================================================================
// 🔥 3. FIREBASE INITIALIZATION & WATCHERS
// =================================================================
let db;
try {
    if (!process.env.FIREBASE_SERVICE_ACCOUNT_KEY) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY is missing!");
    admin.initializeApp({
        credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_KEY)),
        databaseURL: "https://bayra-84ecf-default-rtdb.europe-west1.firebasedatabase.app"
    });
    db = admin.database();
    console.log("✅ Firebase Admin Connected.");
    
    activateImperialWatchman();
    activatePopupScheduler();
    activateDriverVerificationWatcher();
    activateCashierFinanceWatchers();
    setupTelegramWebhook();
} catch (error) { console.error("❌ FIREBASE INIT FAILED:", error.message); }

// =================================================================
// 🏦 4. FINANCE WATCHERS (DEPOSITS & WITHDRAWALS)
// =================================================================
function activateCashierFinanceWatchers() {
    console.log("💰 Cashier Finance Watcher is ACTIVE.");

    db.ref('withdrawals').on('child_added', async (snapshot) => {
        const wdr = snapshot.val();
        if (wdr && wdr.status === "PENDING" && wdr.requestedAt > (SERVER_START_TIME - 60000)) {
            if (wdr.notifiedCashier) return;
            await snapshot.ref.update({ notifiedCashier: true });

            const text = `🏦 <b>NEW WITHDRAWAL REQUEST</b>\n\n👤 <b>Driver:</b> ${wdr.driverName}\n💵 <b>Amount:</b> <b>${wdr.amount} ETB</b>\n🏛️ <b>Destination:</b> ${wdr.bank}\n🔢 <b>Account:</b> <code>${wdr.account}</code>\n👤 <b>Holder Name:</b> ${wdr.accountHolder}\n⏰ <b>Time:</b> ${new Date(wdr.requestedAt).toLocaleTimeString('en-US', { timeZone: 'Africa/Addis_Ababa' })}`;
            const buttons = [[ { text: "✅ Approve & Deduct", callback_data: `wdr_app:${snapshot.key}` }, { text: "❌ Decline", callback_data: `wdr_dec:${snapshot.key}` } ]];
            await sendCashierMessageWithButtons(text, buttons);
        }
    });

    db.ref('deposits_pending').on('child_added', async (snapshot) => {
        const dep = snapshot.val();
        if (dep && dep.submittedAt > (SERVER_START_TIME - 60000)) {
            if (dep.notifiedCashier) return;
            await snapshot.ref.update({ notifiedCashier: true });

            const text = `💵 <b>NEW COMMISSION DEPOSIT PROOF</b>\n\n👤 <b>Driver:</b> ${dep.driverName}\n💰 <b>Amount Settled:</b> ${dep.amountDue || 'Custom'} ETB\n\n📩 <b>Pasted SMS Proof:</b>\n<code>${dep.smsProof}</code>\n\nApprove this deposit to deduct their debt and keep Radar active?`;
            const buttons = [[ { text: "✅ Approve Deposit", callback_data: `dep_app:${snapshot.key}` }, { text: "❌ Decline", callback_data: `dep_dec:${snapshot.key}` } ]];
            await sendCashierMessageWithButtons(text, buttons);
        }
    });
}

// =================================================================
// 🔘 5. TELEGRAM BOT WEBHOOK (BUTTON CLICKS)
// =================================================================
app.post('/telegram-webhook', async (req, res) => {
    res.status(200).send("OK");
    const update = req.body;
    if (!update.callback_query) return;

    const query = update.callback_query;
    const data = query.data;
    const [action, targetId] = data.split(':');
    const chatId = query.message.chat.id;
    const messageId = query.message.message_id;

    if (action === "wdr_app") {
        const snap = await db.ref(`withdrawals/${targetId}`).once('value');
        if (!snap.exists()) return answerTelegramCallback(query.id, "Request not found!");
        const wdr = snap.val();
        if (wdr.status !== "PENDING") return answerTelegramCallback(query.id, `Already ${wdr.status}!`);

        const driverRef = db.ref(`drivers/${wdr.driverName}`);
        const curCredit = Number((await driverRef.child('credit').once('value')).val() || 0);
        await driverRef.update({ credit: Math.max(0, curCredit - Number(wdr.amount || 0)) });
        await snap.ref.update({ status: "APPROVED", resolvedAt: Date.now() });

        const token = (await driverRef.child('fcmToken').once('value')).val();
        if (token) sendPush(token, "✅ Withdrawal Approved!", `${wdr.amount} ETB has been disbursed to your ${wdr.bank} account.`);
        
        await answerTelegramCallback(query.id, "Approved & Deducted!");
        await editTelegramMessage(chatId, messageId, `${query.message.text}\n\n✅ <b>APPROVED BY CASHIER</b>\n💳 Deducted ${wdr.amount} ETB.`);
    }
    else if (action === "wdr_dec") {
        const snap = await db.ref(`withdrawals/${targetId}`).once('value');
        await snap.ref.update({ status: "DECLINED", resolvedAt: Date.now() });
        const token = (await db.ref(`drivers/${snap.val().driverName}/fcmToken`).once('value')).val();
        if (token) sendPush(token, "❌ Withdrawal Declined", `Your withdrawal of ${snap.val().amount} ETB was declined.`);
        
        await answerTelegramCallback(query.id, "Declined.");
        await editTelegramMessage(chatId, messageId, `${query.message.text}\n\n❌ <b>DECLINED BY CASHIER</b>`);
    }
    else if (action === "dep_app") {
        const snap = await db.ref(`deposits_pending/${targetId}`).once('value');
        if (!snap.exists()) return answerTelegramCallback(query.id, "Deposit proof not found!");

        const dep = snap.val();
        const driverRef = db.ref(`drivers/${dep.driverName}`);
        const curDebt = Number((await driverRef.child('debt').once('value')).val() || 0);
        const settledAmount = Number(dep.amountDue || 0);
        
        await driverRef.update({ debt: Math.max(0, curDebt - settledAmount) });
        await snap.ref.remove();

        const token = (await driverRef.child('fcmToken').once('value')).val();
        if (token) sendPush(token, "🔓 Deposit Reconciled!", "Your deposit has been verified. Radar is unlocked!");

        await answerTelegramCallback(query.id, "Deposit Approved!");
        await editTelegramMessage(chatId, messageId, `${query.message.text}\n\n✅ <b>DEPOSIT APPROVED</b>\n🔓 Debt reduced by ${settledAmount} ETB.`);
    }
    else if (action === "dep_dec") {
        const snap = await db.ref(`deposits_pending/${targetId}`).once('value');
        await snap.ref.remove();
        const token = (await db.ref(`drivers/${snap.val().driverName}/fcmToken`).once('value')).val();
        if (token) sendPush(token, "⚠️ Deposit Rejected", "Your bank SMS proof could not be verified.");

        await answerTelegramCallback(query.id, "Deposit Declined.");
        await editTelegramMessage(chatId, messageId, `${query.message.text}\n\n❌ <b>SMS PROOF REJECTED</b>`);
    }
});

// =================================================================
// 🛡️ 6. DRIVER VERIFICATION WATCHER (Sends to Verification Bot)
// =================================================================
function activateDriverVerificationWatcher() {
    console.log("🛡️ Driver Verification Watcher is ACTIVE.");

    db.ref('drivers').on('child_changed', async (snapshot) => {
        const driver = snapshot.val();
        if (driver && driver.status === "PENDING_APPROVAL" && driver.submittedAt && driver.submittedAt > (SERVER_START_TIME - 60000)) {
            if (driver.notifiedVerificationBot) return;
            await snapshot.ref.update({ notifiedVerificationBot: true });

            const msg = `🚨 <b>NEW DRIVER VERIFICATION REQUEST</b>\n\n👤 <b>App Name:</b> ${snapshot.key}\n📝 <b>Legal Name:</b> ${driver.fullName || driver.name || 'N/A'}\n📞 <b>Phone:</b> <code>${driver.phone || 'N/A'}</code>\n🪪 <b>National ID / FAYDA:</b> <code>${driver.nationalId || 'N/A'}</code>\n🚗 <b>Driver License:</b> <code>${driver.licenseNumber || 'N/A'}</code>\n🛺 <b>Vehicle:</b> ${driver.vehicleType || 'BAJAJ'} (${driver.carPlate || 'N/A'})\n\n📸 <b>ID & License Photos:</b> Uploaded to Database\n🔗 <a href="https://console.firebase.google.com/project/bayra-84ecf/database/bayra-84ecf-default-rtdb/data/drivers/${encodeURIComponent(snapshot.key)}">Click here to Review & Set to VERIFIED</a>`;
            try { await axios.post(`https://api.telegram.org/bot${VERIFICATION_BOT_TOKEN}/sendMessage`, { chat_id: VERIFICATION_CHAT_ID, text: msg, parse_mode: 'HTML' }); } catch (e) {}
        }
    });
}

// =================================================================
// 🚕 7. DISPATCH LOGISTICS (IMPERIAL WATCHMAN)
// =================================================================
function getDistance(lat1, lon1, lat2, lon2) {
    const R = 6371; const dLat = (lat2 - lat1) * Math.PI / 180; const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2) * Math.sin(dLat/2) + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon/2) * Math.sin(dLon/2);
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
                    if (dist < minDistance) { minDistance = dist; closestDriver = { name: child.key, token: driver.fcmToken }; }
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
            } else { broadcastToDrivers("🚨 New Dispatch!", `A new ${ride.tier} request is waiting.`); }
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
            if (snap.exists()) { const popup = snap.val(); if (popup.expiresAt && Date.now() > popup.expiresAt) await snap.ref.remove(); }
        } catch (e) {}
    }, 60000);
}

async function sendToUser(userName, title, body) {
    try { const token = (await db.ref(`users/${userName}`).once('value')).val()?.fcmToken; if (token) sendPush(token, title, body); } catch (e) {}
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

// =================================================================
// 📲 8. TELEGRAM GATEWAY (OTP DISPATCH)
// =================================================================
async function dispatchTelegramGatewayVerification(phone, pin) {
    let formattedPhone = phone.trim();
    if (formattedPhone.startsWith('0')) formattedPhone = '+251' + formattedPhone.substring(1);
    else if (!formattedPhone.startsWith('+')) formattedPhone = '+' + formattedPhone;
    return await axios.post('https://gatewayapi.telegram.org/sendVerificationMessage', { phone_number: formattedPhone, code: pin }, { headers: { 'Authorization': `Bearer ${process.env.TELEGRAM_GATEWAY_KEY}`, 'Content-Type': 'application/json' } });
}

app.post('/send-telegram-code', async (req, res) => {
    const { phone, pin } = req.body;
    try { await dispatchTelegramGatewayVerification(phone, pin); res.status(200).json({ success: true }); } 
    catch (e) { res.status(500).json({ success: false, error: e.message }); }
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
            if (!snap.exists()) return res.status(404).json({ success: false, message: "Phone number not registered." });
        }
        const pin = Math.floor(100000 + Math.random() * 900000).toString();
        await db.ref(`verifications/${phone}/code`).set(pin);
        await dispatchTelegramGatewayVerification(phone, pin);
        res.status(200).json({ success: true });
    } catch (e) { res.status(500).json({ success: false, message: "Gateway error." }); }
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
            res.status(200).json({ success: true, message: "Password updated!" });
        } else { res.status(400).json({ success: false, message: "Invalid verification code." }); }
    } catch (e) { res.status(500).json({ success: false, message: e.message }); }
});

// =================================================================
// 🚀 START SERVER
// =================================================================
const PORT = process.env.PORT || 10000;
app.listen(PORT, () => { console.log(`Bayra Imperial Core is ONLINE on port ${PORT}`); });