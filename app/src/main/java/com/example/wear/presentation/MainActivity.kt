package com.example.wear.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wear.presentation.theme.WearTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.tasks.await
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        checkPermissions()
        setTheme(android.R.style.Theme_DeviceDefault)

        val serviceIntent = Intent(this, SensorForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            WearApp()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        }
    }

    @Composable
    fun WearApp() {
        var isSending by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("서비스가 일시정지 중입니다.") }
        val context = LocalContext.current

        WearTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        Button(
                            onClick = {
                                val intent = Intent(context, SensorForegroundService::class.java)
                                intent.action = "com.example.wear.ACTION_START_TIMER"
                                context.startService(intent)
                                isSending = true
                                statusText = "서비스가 실행 중입니다."
                                val database = FirebaseDatabase.getInstance("https://wear-b9cbc-default-rtdb.firebaseio.com/")
                                val deleteSensor = database.getReference("watch_sensor_data").removeValue()
                                val deleteStopSignal = database.getReference("watch_signal/stop").removeValue()
                                Tasks.whenAll(deleteSensor, deleteStopSignal).addOnSuccessListener {
                                    database.getReference("watch_signal/start").setValue(ServerValue.TIMESTAMP)
                                }
                            },
                            enabled = !isSending,
                            modifier = Modifier.padding(8.dp)
                        ) { Text("시작", fontSize = 14.sp) }

                        Button(
                            onClick = {
                                val intent = Intent(context, SensorForegroundService::class.java)
                                intent.action = "com.example.wear.ACTION_STOP_TIMER"
                                context.startService(intent)
                                isSending = false
                                statusText = "서비스가 일시정지 중입니다."
                                val database = FirebaseDatabase.getInstance("https://wear-b9cbc-default-rtdb.firebaseio.com/")
                                val deleteStartSignal = database.getReference("watch_signal/start").removeValue()
                                Tasks.whenAll(deleteStartSignal).addOnSuccessListener {
                                    database.getReference("watch_signal/stop").setValue(ServerValue.TIMESTAMP)
                                }

                            },
                            enabled = isSending,
                            modifier = Modifier.padding(8.dp)
                        ) { Text("종료", fontSize = 14.sp) }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        statusText,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(8.dp)
                    )

                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = { sendHiddenSignal() },
                            modifier = Modifier.padding(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                            )
                        ) {
                        }

                        Button(
                            onClick = { sendHiddenSignal2() },
                            modifier = Modifier.padding(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                            )
                        ) {
                        }
                    }
                }
            }
        }
    }
    private fun sendHiddenSignal() {
        val database = FirebaseDatabase.getInstance("https://<my-project>.firebaseio.com/")
        val ref = database.getReference("hidden_signal")
        val ts = System.currentTimeMillis()
        ref.setValue(ts)

        GlobalScope.launch(Dispatchers.Main) {
            delay(1000)
            ref.removeValue()
        }
    }
    private fun sendHiddenSignal2() {
        val database = FirebaseDatabase.getInstance("https://<my-project>.firebaseio.com/")
        val ref = database.getReference("hidden_signal2")
        val ts = System.currentTimeMillis()
        ref.setValue(ts)

        GlobalScope.launch(Dispatchers.Main) {
            delay(1000)
            ref.removeValue()
        }

    }
}