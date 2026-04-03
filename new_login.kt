@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(name: String, phone: String, email: String, onLogin: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var p by remember { mutableStateOf(phone) }; var e by remember { mutableStateOf(email) }
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Bayra Travel", fontSize = 28.sp, fontWeight = FontWeight.Black, color = IMPERIAL_BLUE)
        Text("Arba Minch Digital Transport", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
        OutlinedTextField(n, { n = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(modifier = Modifier.height(16.dp))
        Text("Provide Phone OR Email for your PIN:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        OutlinedTextField(p, { p = it }, label = { Text("Phone Number") }, placeholder = { Text("+251...") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(e, { e = it }, label = { Text("Email Address") }, placeholder = { Text("example@mail.com") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { if(n.length > 2 && (p.length >= 9 || e.contains("@"))) onLogin(n, p, e) else Toast.makeText(ctx, "Name + (Phone or Email) required!", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = IMPERIAL_BLUE)) { Text("GET VERIFICATION PIN", fontWeight = FontWeight.ExtraBold) }
    }
}
