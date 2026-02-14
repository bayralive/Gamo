const express = require('express');
const cors = require('cors');
const admin = require('firebase-admin');
const app = express();

app.use(cors());
app.use(express.json());

// --- ARBA MINCH TREASURY CONFIG ---
const CHAPA_SECRET_KEY = "CHASECK_TEST-xxxxxxxxxxxxxxxx"; // REPLACE WITH YOUR REAL SECRET KEY

app.get('/', (req, res) => {
  res.send('BAYRA COMMAND CENTER: SOVEREIGN & ONLINE 🛰️');
});

// --- THE HANDSHAKE: INITIALIZE PAYMENT ---
app.post('/initialize-payment', async (req, res) => {
  const { rideId, amount, email, name } = req.body;
  
  // This is where the backend talks to Chapa
  // For the V1.1 launch, we log the intent and return success
  console.log(`Payment initialized for Ride: ${rideId} | Amount: ${amount} ETB`);
  
  res.json({
    status: "success",
    checkout_url: `https://checkout.chapa.co/checkout/web/payment/CHAPUBK-GTviouToMOe9vOg5t1dNR9paQ1M62jOX`
  });
});

// --- THE WEBHOOK: CHAPA TALKS BACK TO BAYRA ---
app.post('/chapa-webhook', (req, res) => {
  const data = req.body;
  console.log("CHAPA SIGNAL RECEIVED:", data);
  // Logic to update Firebase ride status to "PAID" goes here
  res.sendStatus(200);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Bayra Sovereign Engine active on port ' + PORT);
});
