const express = require('express');
const cors = require('cors');
const axios = require('axios');
const app = express();

app.use(cors());
app.use(express.json());

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_SECRET_KEY = process.env.CHAPA_SECRET_KEY; 

app.get('/', (req, res) => {
  res.send('BAYRA TREASURY: READY 🛰️');
});

app.post('/initialize-payment', async (req, res) => {
  const { amount, name, phone, email, rideId } = req.body;
  
  // ROOT FIX: Generate a dummy email if user didn't provide one, but prefer real email
  const userEmail = email || `customer-${Date.now()}@bayra.et`;
  const tx_ref = `BAYRA-${rideId}-${Date.now()}`;

  console.log(`Processing Payment: ${amount} ETB | User: ${name} | Email: ${userEmail}`);

  try {
    const response = await axios.post(CHAPA_URL, {
      amount: amount,
      currency: "ETB",
      email: userEmail,
      first_name: name || "Passenger",
      last_name: "Bayra",
      phone_number: phone,
      tx_ref: tx_ref,
      callback_url: "https://bayra-backend-eu.onrender.com/chapa-webhook",
      "customization[title]": "Bayra Trip",
      "customization[description]": "Arba Minch Transport Service"
    }, {
      headers: {
        Authorization: `Bearer ${CHAPA_SECRET_KEY}`,
        'Content-Type': 'application/json'
      }
    });

    console.log("✅ Chapa Success:", response.data.status);
    res.json(response.data.data); 

  } catch (error) {
    // 🔥 ROOT CAUSE LOGGING
    if (error.response) {
      console.error("❌ CHAPA REJECTED REQUEST:", JSON.stringify(error.response.data));
      res.status(400).json({ status: "failed", error: error.response.data.message });
    } else {
      console.error("❌ NETWORK ERROR:", error.message);
      res.status(500).json({ status: "failed", error: "Internal Server Error" });
    }
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Bayra Sovereign Engine running on port ' + PORT);
});
