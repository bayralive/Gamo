const express = require('express');
const cors = require('cors');
const app = express();

app.use(cors());
app.use(express.json());

app.get('/', (req, res) => {
  res.send('Bayra Arba Minch Command Center: ONLINE 🛰️');
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Sovereign Backend running on port ' + PORT);
});
