const express = require('express');
const cors = require('cors');
const axios = require('axios');
const app = express();

app.use(cors());
app.use(express.json());

const CHAPA_URL = "https://api.chapa.co/v1/transaction/initialize";
const CHAPA_SECRET_KEY = process.env.CHAPA_SECRET_KEY; 

app.get('/', (req, res) => {
  res.send('BAYRA TREASURY: ONLINE 🛰️');
});

app.post('/initialize-payment', async (req, res) => {
  const { amount, name, phone } = req.body;
  
  // Create a unique reference for this specific transaction
  const tx_ref = `BAYRA-${Date.now()}`;

  try {
    console.log(`Requesting Chapa Link for: ${name} | Amount: ${amount} ETB`);
    
    const response = await axios.post(CHAPA_URL, {
      amount: amount,
      currency: "ETB",
      email: "customer@bayra.et",
      first_name: name || "Passenger",
      last_name: "Gamo",
      tx_ref: tx_ref,
      callback_url: "https://bayra-backend-eu.onrender.com/chapa-webhook",
      "customization[title]": "Bayra Payment",
      "customization[description]": "Ride Payment for Arba Minch"
    }, {
      headers: {
        Authorization: `Bearer ${CHAPA_SECRET_KEY}`,
        'Content-Type': 'application/json'
      }
    });

    // Send the REAL Chapa link back to the phone
    res.json(response.data.data); 

  } catch (error) {
    console.error("CHAPA ERROR:", error.response ? error.response.data : error.message);
    res.status(500).json({ status: "failed", error: "Chapa Handshake Failed" });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Bayra Sovereign Engine active on port ' + PORT);
});
