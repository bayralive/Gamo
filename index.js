const express = require('express');
const cors = require('cors');
const axios = require('axios');
const app = express();

app.use(cors());
app.use(express.json());

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_SECRET_KEY = process.env.CHAPA_SECRET_KEY; 

app.get('/', (req, res) => {
  res.send('BAYRA TREASURY: FULLY OPERATIONAL 🛰️');
});

app.post('/initialize-payment', async (req, res) => {
  const { amount, name, phone, rideId } = req.body;
  
  // Validation check
  if (!phone || phone.length < 9) {
    return res.status(400).json({ status: "failed", error: "Valid Phone Number Required" });
  }

  const tx_ref = `BAYRA-${rideId || Date.now()}`;

  try {
    console.log(`Treasury request: ${name} | ${phone} | ${amount} ETB`);
    
    const response = await axios.post(CHAPA_URL, {
      amount: amount,
      currency: "ETB",
      email: "payment@bayra.et", // System email
      first_name: name || "Passenger",
      last_name: "Gamo",
      phone_number: phone, // 🔥 THE MISSING LINK
      tx_ref: tx_ref,
      callback_url: "https://bayra-backend-eu.onrender.com/chapa-webhook",
      "customization[title]": "Bayra Ride Payment",
      "customization[description]": `Trip ID: ${rideId}`
    }, {
      headers: {
        Authorization: `Bearer ${CHAPA_SECRET_KEY}`,
        'Content-Type': 'application/json'
      }
    });

    res.json(response.data.data); 

  } catch (error) {
    console.error("CHAPA ERROR:", error.response ? JSON.stringify(error.response.data) : error.message);
    res.status(500).json({ status: "failed", error: "Chapa Handshake Failed" });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Bayra Sovereign Engine running on port ' + PORT);
});
