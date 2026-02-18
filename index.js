const express = require('express');
const axios = require('axios');
const app = express();
app.use(express.json());

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_AUTH = "CHAPUBK-rsiltQmE5SqOK21Qqs6UTV7vjCsXycBc"; // Your Key

app.post('/initialize-payment', async (req, res) => {
    const { amount, email, name, rideId } = req.body;

    try {
        const response = await axios.post(CHAPA_URL, {
            amount: amount,
            currency: "ETB",
            email: email,
            first_name: name,
            last_name: "Customer",
            tx_ref: rideId,
            callback_url: "https://bayra-backend-eu.onrender.com/verify-payment/" + rideId,
            "customization[title]": "Bayra Travel",
            "customization[description]": "Arba Minch Sovereign Transport"
        }, {
            headers: { Authorization: `Bearer ${CHAPA_AUTH}` }
        });

        res.json(response.data);
    } catch (error) {
        res.status(500).json({ status: "failed", message: error.message });
    }
});

app.listen(process.env.PORT || 3000, () => console.log("Bayra Treasury Online"));