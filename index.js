const express = require('express');
const cors = require('cors');
const axios = require('axios');
const app = express();

app.use(cors());
app.use(express.json());

// Pull Secret Key from Render Environment
const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_SECRET_KEY = process.env.CHAPA_SECRET_KEY; 

app.get('/', (req, res) => {
  res.send('BAYRA SOVEREIGN BACKEND: v1.1 ACTIVE 🟢');
});

app.post('/initialize-payment', async (req, res) => {
  const { amount, name, phone, email, rideId } = req.body;
  
  // Use provided email or generate a unique placeholder
  const userEmail = email && email.length > 5 ? email : `customer-${Date.now()}@bayra.et`;
  const tx_ref = `BAYRA-${rideId}-${Date.now()}`;

  console.log(`⚡ PROCESSING: ${amount} ETB | User: ${name} | Ref: ${tx_ref}`);

  if (!CHAPA_SECRET_KEY) {
    console.error("❌ CRITICAL: CHAPA_SECRET_KEY is missing in Render Settings!");
    return res.status(500).json({ status: "failed", error: "Server Misconfiguration" });
  }

  try {
    const response = await axios.post(CHAPA_URL, {
      amount: amount.toString(),
      currency: "ETB",
      email: userEmail,
      first_name: name || "Passenger",
      last_name: "Bayra",
      phone_number: phone || "0900000000",
      tx_ref: tx_ref,
      callback_url: "https://bayra-backend-eu.onrender.com/chapa-webhook",
      "customization[title]": "Bayra Trip Payment",
      "customization[description]": "Arba Minch Transport"
    }, {
      headers: {
        Authorization: `Bearer ${CHAPA_SECRET_KEY}`,
        'Content-Type': 'application/json'
      }
    });

    console.log("✅ CHAPA SUCCESS. Link Generated.");
    res.json(response.data.data); 

  } catch (error) {
    // Detailed Error Logging
    if (error.response) {
      console.error("❌ CHAPA REJECTION:", JSON.stringify(error.response.data));
      res.status(400).json({ status: "failed", error: error.response.data.message });
    } else {
      console.error("❌ NETWORK ERROR:", error.message);
      res.status(500).json({ status: "failed", error: "Internal Connection Error" });
    }
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Bayra Engine v1.1 listening on port ${PORT}`);
});
