package com.example.wear.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import android.os.PowerManager
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import kotlin.math.roundToInt

class SensorForegroundService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var heartAdjust = 0
    private var actAdjust = 0
    private var stressAdjust = 0
    private var tensionAdjust = 0

    private val sensorValues = mutableMapOf(
        "심박수" to "-",
        "걸음 수" to "-",
        "걸음 감지" to "0",
        "가속도계" to "-",
        "자이로스코프" to "-",
        "자기장" to "-",
        "기압" to "-",
        "조도" to "-",
        "스트레스지수" to "0",
        "긴장도" to "0",
        "운동량" to "0",
        "총점" to "0"
    )
    private var sendJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        createNotificationChannel()
        startForeground(1, buildNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorService:Wakelock")
        wakeLock?.acquire()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorList = listOf(
            Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_LIGHT
        )
        sensorList.mapNotNull { sensorManager.getDefaultSensor(it) }
            .forEach { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        Log.d("WearLog", "포그라운드 서비스에서 센서 리스너 등록 완료")
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        sendJob?.cancel()
        releaseWakeLock()
        Log.d("WearLog", "포그라운드 서비스 종료, 센서 리스너 해제 및 반복전송 중단, WakeLock 해제")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.example.wear.ACTION_START_TIMER" -> {
                startSending()
            }
            "com.example.wear.ACTION_STOP_TIMER" -> {
                stopSending()
            }
        }
        return START_STICKY
    }

    private fun startSending() {
        sendJob?.cancel()
        acquireWakeLock()
        sendJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                sendAllSensorData()
                delay(1000)
            }
        }
        Log.d("WearLog", "센서 데이터 전송 시작")
    }

    private fun stopSending() {
        sendJob?.cancel()
        sendJob = null
        releaseWakeLock()
        Log.d("WearLog", "센서 데이터 전송 중단")
    }

    private fun acquireWakeLock() {
        Log.d("WearLog", "acquireWakeLock() called. 현재 held 상태: ${wakeLock?.isHeld}")
        if (wakeLock?.isHeld == true) {
            Log.d("WearLog", "이미 held 상태")
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorService:Wakelock")
        wakeLock?.acquire()
        Log.d("WearLog", "WakeLock 획득")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                Log.d("WearLog", "WakeLock 해제")
            } catch (e: Exception) {
                Log.w("WearLog", "WakeLock 해제 예외: $e")
            }
        }
        wakeLock = null
    }

    private var latestHeartRate: Float = 0f
    private var latestStepDetected: Boolean = false
    private var latestLight: Float = 0f
    private var latestAccRms: Float = 0f
    private var latestGyroRms: Float = 0f
    private var prevStepCount: Float = 0f
    private var latestStepCount: Float = 0f

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val sensorType = event.sensor.type
        val valueString = event.values.joinToString(", ") { "%.2f".format(it) }
        when (sensorType) {
            Sensor.TYPE_HEART_RATE -> {
                sensorValues["심박수"] = valueString
                latestHeartRate = event.values[0]
                val adjusted = latestHeartRate + heartAdjust
                sensorValues["심박수"] = "%.2f".format(adjusted)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                sensorValues["걸음 수"] = valueString
                prevStepCount = latestStepCount
                latestStepCount = event.values[0]
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                sensorValues["걸음 감지"] = valueString
                latestStepDetected = (event.values[0] == 1f)
                if (latestStepDetected) {
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(1000)
                        latestStepDetected = false
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                sensorValues["가속도계"] = valueString
                latestAccRms = rms(event.values)
            }
            Sensor.TYPE_GYROSCOPE -> {
                sensorValues["자이로스코프"] = valueString
                latestGyroRms = rms(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> sensorValues["자기장"] = valueString
            Sensor.TYPE_PRESSURE -> sensorValues["기압"] = valueString
            Sensor.TYPE_LIGHT -> {
                sensorValues["조도"] = valueString
                latestLight = event.values[0]
            }
        }
        if (sensorType == Sensor.TYPE_STEP_COUNTER) {
            prevStepCount = latestStepCount
            latestStepCount = event.values[0]
        }
    }
    private fun normalizeHeartRate(hr: Float): Float {
        val clipped = hr.coerceIn(30f, 180f)
        return ((clipped - 30f) / (180f - 30f)) * 100f
    }

    private fun rms(arr: FloatArray): Float {
        return kotlin.math.sqrt(arr.map { it * it }.average().toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private suspend fun sendAllSensorData() {
        fetchPlusValuesFromFirebase()
        val stepDelta = (latestStepCount - prevStepCount).toInt().coerceAtLeast(0)

        val stress = calcStress(latestHeartRate, latestStepDetected, latestLight)
        val tension = calcTension(latestHeartRate, latestAccRms, latestGyroRms)
        val activity = calcActivity(stepDelta, latestAccRms)
        val totalScore = calcTotalScore(latestHeartRate + heartAdjust, stress, tension)

        sensorValues["스트레스지수"] = stress.toString()
        sensorValues["긴장도"] = tension.toString()
        sensorValues["운동량"] = activity.toString()
        sensorValues["총점"] = totalScore.toString()

        val dataJson = JSONObject(sensorValues.toMap()).toString()
        Log.d("WearLog", "sendAllSensorData() 진입, 데이터: $dataJson")
        try {
            val database = FirebaseDatabase.getInstance("https://<my-project>.firebaseio.com/")
            val ref = database.getReference("watch_sensor_data")
            val timestamp = System.currentTimeMillis()
            ref.child("$timestamp").setValue(sensorValues.toMap())
                .addOnSuccessListener { Log.d("WearLog", "Firebase 업로드 성공: $dataJson") }
                .addOnFailureListener { e -> Log.e("WearLog", "Firebase 업로드 실패: $e") }
        } catch (e: Exception) {
            Log.e("WearLog", "Firebase RTDB 업로드 예외: $e")
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "sensor_channel")
            .setContentTitle("워치 센서 동작 중")
            .setContentText("데이터 동기화 중")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()
    }

    private suspend fun fetchPlusValuesFromFirebase() {
        val database = FirebaseDatabase.getInstance("https://<my-project>.firebaseio.com/")
        val ref = database.getReference("plusvalue")
        try {
            val snapshot = ref.get().await()
            heartAdjust = snapshot.child("심박수").getValue(Int::class.java) ?: 0
            stressAdjust = snapshot.child("스트레스지수").getValue(Int::class.java) ?: 0
            actAdjust = snapshot.child("운동량").getValue(Int::class.java) ?: 0
            tensionAdjust = snapshot.child("긴장도").getValue(Int::class.java) ?: 0
            Log.d("WearLog", "plusvalue 값 적용됨: heart=$heartAdjust, stress=$stressAdjust, act=$actAdjust, tension=$tensionAdjust")
        } catch (e: Exception) {
            Log.e("WearLog", "plusvalue 값 가져오기 실패: $e")
            heartAdjust = 0
            stressAdjust = 0
            actAdjust = 0
            tensionAdjust = 0
        }
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sensor_channel", "센서 서비스", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
    private fun calcStress(heartRate: Float, stepDetected: Boolean, light: Float): Int {
        var score = ((heartRate - 65) * 0.7).coerceAtLeast(0.0)
        if (!stepDetected) score += 3
        if (light < 100) score += 2
        score += stressAdjust
        return score.coerceIn(0.0, 100.0).toInt()
    }
    private var prevAccRms: Float = 9.8f
    private var prevGyroRms: Float = 0f
    private val shockAccBuffer = ArrayDeque<Boolean>()
    private val shockGyroBuffer = ArrayDeque<Boolean>()
    private val shockBufferSize = 6

    private fun calcTension(heartRate: Float, accRms: Float, gyroRms: Float): Int {
        val deltaAcc = kotlin.math.abs(accRms - prevAccRms)
        val deltaGyro = kotlin.math.abs(gyroRms - prevGyroRms)
        prevAccRms = accRms
        prevGyroRms = gyroRms

        shockAccBuffer.addLast(deltaAcc > 3.0)
        shockGyroBuffer.addLast(deltaGyro > 1.2)
        if (shockAccBuffer.size > shockBufferSize) shockAccBuffer.removeFirst()
        if (shockGyroBuffer.size > shockBufferSize) shockGyroBuffer.removeFirst()

        val shockAccCount = shockAccBuffer.count { it }
        val shockGyroCount = shockGyroBuffer.count { it }

        val hrFactor = ((heartRate - 70) * 0.2).coerceAtLeast(0.0)
        val changeFactor = (deltaAcc * 1.2) + (deltaGyro * 0.9)
        var score = hrFactor + changeFactor + tensionAdjust

        if (shockAccCount >= 5 || shockGyroCount >= 5) score += 40

        return score.coerceIn(0.0, 100.0).toInt()
    }
    private val accRmsBuffer = ArrayDeque<Float>()
    private val bufferSize = 10

    private fun calcActivity(stepDelta: Int, accRms: Float): Int {
        accRmsBuffer.addLast(accRms)
        if (accRmsBuffer.size > bufferSize) accRmsBuffer.removeFirst()
        val avgAcc = (accRmsBuffer.sum() / accRmsBuffer.size) - 9.8f
        val movePart = avgAcc.coerceAtLeast(0f) * 10
        val base = stepDelta * 8
        var total = base + movePart + actAdjust

        if (latestStepDetected) {
            total += 30
        }
        return total.coerceIn(0.0F, 100.0F).toInt()
    }
    private fun calcTotalScore(hr: Float, stress: Int, tension: Int): Int {
        val normHr = normalizeHeartRate(hr)
        val total = 0.3f * normHr + 0.3f * stress + 0.4f * tension
        return total.roundToInt().coerceIn(0, 100)
    }
}

