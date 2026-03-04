console.log(`💰 [PAYMENT] Initializing real Chapa request for ${name}: ${amount} ETB`);

try {
    const response = await axios.post(CHAPA_URL, {
        amount: amount,
        currency: "ETB",
        email: email,
        first_name: name,
        tx_ref: tx_ref,
        callback_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
        return_url: `https://bayra-backend-eu.onrender.com/verify-payment/${rideId}/${tx_ref}`,
        "customization[title]": "Bayra Prestige Ride",
        "customization[description]": "Payment for your elite journey"
    }, CHAPA_AUTH);

    // Chapa gives us a real checkout_url
    res.json({ status: "success", data: { checkout_url: response.data.data.checkout_url } });
    
} catch (e) {
    console.error("❌ [CHAPA] Failed to get link:", e.response?.data || e.message);
    res.status(500).json({ status: "failed", message: "Chapa Gateway Offline" });
}