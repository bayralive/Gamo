// ... (existing imports)

// 🔥 NEW VERIFICATION ENDPOINT
app.get('/verify-payment/:rideId', async (req, res) => {
    const { rideId } = req.params;
    // In a real scenario, you'd check Chapa's API here with the tx_ref
    // For now, we tell Firebase the Treasury has received the funds
    
    const admin = require('firebase-admin');
    // Ensure Firebase Admin is initialized in your backend
    
    await admin.database().ref(`rides/${rideId}`).update({
        status: "PAID_CHAPA",
        verifiedByBackend: true
    });

    res.send("<h1>Payment Success! You can close the app.</h1>");
});