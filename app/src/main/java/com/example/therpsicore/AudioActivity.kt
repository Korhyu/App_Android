package com.example.therpsicore

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTrack.PLAYSTATE_PLAYING
import android.media.AudioTrack.PLAYSTATE_STOPPED
import android.net.ConnectivityManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.Process.THREAD_PRIORITY_AUDIO
import android.util.Log
import android.widget.SeekBar
import android.widget.Switch
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.math.sin



class AudioActivity : AppCompatActivity() {

    private val RxMaxBuf=120*16*10
    private val power = 1
    private var bufferPlay = ShortArray(RxMaxBuf)
    private var prueba = ByteArray(999)
    private var outBufferRx:Int=0
    private var inBufferRx:Int=0
    private var countBufferRx:Int=0
    private var inBufferPlay:Int=0
    private var outBufferPlay:Int=0
    private var countBufferPlay:Int=0
    private var check=12

    var datoCrudo=ByteArray(500 * 2)
    var total=0
    var recp=0
    var plays=0
    var finalTime=0
    var carga=0


    //Indices
    var last_RX = 0                                     // Ultimo dato del buffer Rx Recivido
    var last_Pr = 0                                     // Ultimo dato del buffer Rx Mandado a procesar
    var last_Au = 0                                     // Ultimo dato del buffer enviado Audio sink


    // Datos del audio
    private var samfreq = 40000
    private var audiobuffer = 2000
    private var sincbuffsize = 120
    private val muestrasEnvio = 120


    //Variables y valores auxiliares
    var buff_recv = 481
    var audio_chunk_ms = 10                                     // Bloque de audio a escribir en el sink expresado en ms
    val audio_chunk_sam = audio_chunk_ms * (samfreq/1000)       // Audio chunk expresado en muestras
    val recepcion = true                                       // Cambiar esto a true cuando se reciba desde la BBB
    var bufSin_index = audio_chunk_sam                          // Arranco a contar despues de la primer copia en inicializacion


    //Buffers
    private var bufferRx = ByteArray(audio_chunk_sam*100)          //ByteArray(buff_recv*100)
    private var bufferProcesado = ShortArray(audio_chunk_sam)
    private var audio_chunk = ShortArray(audio_chunk_sam)
    private var audio_ch1 = ShortArray(audio_chunk_sam)
    private var audio_ch2 = ShortArray(audio_chunk_sam)
    private var audio_ch3 = ShortArray(audio_chunk_sam)
    private var audio_ch4 = ShortArray(audio_chunk_sam)



    // Buffers de tonos auxiliares
    private val bufSin1 = createSinWaveBuffer(500.0, 3000)
    private val bufSin2 = createSinWaveBuffer(1000.0, 3000)
    private val bufSin3 = createSinWaveBuffer(1500.0, 3000)
    private val bufSin4 = createSinWaveBuffer(2000.0, 3000)


    //Estado de los switches y seekbars
    var sw_status = arrayOf(0, 0, 0, 0)    //Estado de los switchs para evitar el uso de funciones mas complejas
    var sb_vol1 = arrayOf(0, 0, 0, 0)       //Estado de los sliders de volumen


    // Variables auxiliares de audio
    var canCanales = sw_status.sum()
    var dclvl = 2047 * canCanales
    var knorm = 0


    //Semaforos
    private val semProce: Semaphore = Semaphore(0, false)         //Dato listo para procesarse, puedo tener varios listos para procesarse
    private val semChunk: Semaphore = Semaphore(0, false)           //Chunk de audio listo para write



    var mAudioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC, samfreq, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, audiobuffer,
            AudioTrack.MODE_STREAM
    )


    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        init_audio()

        rxRcv().execute()


        // Pasa del bufRx a los diferentes canales
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Procesamiento") {
            while(power==1) {

                if (recepcion == true) {

                    semProce.acquireUninterruptibly()                                   //Espero esten listos los datos desde la recepcion

                    for (i in 0 until audio_chunk_sam step 8) {
                        audio_ch1[i/8] = ((bufferRx[last_Pr + i + 0].toUByte().toInt() + (bufferRx[last_Pr + i + 1].toInt() shl 8)) - 2047).toShort()
                        audio_ch2[i/8] = ((bufferRx[last_Pr + i + 2].toUByte().toInt() + (bufferRx[last_Pr + i + 3].toInt() shl 8)) - 2047).toShort()
                        audio_ch3[i/8] = ((bufferRx[last_Pr + i + 4].toUByte().toInt() + (bufferRx[last_Pr + i + 5].toInt() shl 8)) - 2047).toShort()
                        audio_ch4[i/8] = ((bufferRx[last_Pr + i + 6].toUByte().toInt() + (bufferRx[last_Pr + i + 7].toInt() shl 8)) - 2047).toShort()
                    }

                    semChunk.release()

                }
                else
                {
                    if (canCanales != 0) {
                        for (i in 0 until audio_chunk_sam step 1) {
                            audio_ch1[i] = bufSin1[last_Pr + i]
                            audio_ch2[i] = bufSin2[last_Pr + i]
                            audio_ch3[i] = bufSin3[last_Pr + i]
                            audio_ch4[i] = bufSin4[last_Pr + i]
                        }

                        semChunk.release()
                    }


                    // Demora que simula la recepcion de datos nuevos, ese "-1" hace que luego
                    // de varias reproducciones genere latencia en el encendido y apagaoo de los switchs
                    Thread.sleep(audio_chunk_ms.toLong()-1)
                }


                last_Pr += audio_chunk_sam
                last_Pr %= bufferRx.size

            }
        }

        // Thread que pasa de los buffers de canales al audio sink
        // Ademas afecta por los switchs y volumen
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Envio") {
            while(power==1) {

                semChunk.acquireUninterruptibly()                          //Pido el semaforo de envio, si no esta libre es que no hay datos para enviar al sink


                if (canCanales == 0)
                {
                    // SI no hay canales encendidos, no tiene sentido liberar el semaforo ni sacar las cuentas
                    for (i in 0 until (audio_chunk_sam-1)) {
                        audio_chunk[i] = 0.toShort()
                    }
                    mAudioTrack.stop()
                    Thread.yield()                                          //Suspendo el thread hasta que cambien los switchs
                }
                else
                {
                    var aux = 0

                    if (recepcion == false) {
                        for (i in 0 until (audio_chunk_sam - 1)) {

                            //TODO agregar el volumen
                            aux += audio_ch1[last_Au + i] * sw_status[0]          //Canal 1
                            aux += audio_ch2[last_Au + i] * sw_status[1]          //Canal 2
                            aux += audio_ch3[last_Au + i] * sw_status[2]          //Canal 3
                            aux += audio_ch4[last_Au + i] * sw_status[3]          //Canal 4


                            audio_chunk[i] = ((aux - dclvl) * knorm).toShort()
                            aux = 0
                        }
                    }
                    else
                    {
                        for (i in 0 until (audio_chunk_sam/2-1)) {

                            //TODO agregar el volumen
                            aux += audio_ch1[last_Au + i] * sw_status[0]          //Canal 1
                            aux += audio_ch2[last_Au + i] * sw_status[1]          //Canal 2
                            aux += audio_ch3[last_Au + i] * sw_status[2]          //Canal 3
                            aux += audio_ch4[last_Au + i] * sw_status[3]          //Canal 4


                            audio_chunk[i] = ((aux - dclvl) * knorm).toShort()
                            aux = 0

                            last_Au += audio_chunk_sam
                            last_Au %= audio_ch1.size
                        }
                    }


                    // Mando al audio sink
                    mAudioTrack.write(audio_chunk, 0, audio_chunk_sam, AudioTrack.WRITE_BLOCKING)

                    if(mAudioTrack.playState == PLAYSTATE_STOPPED)
                    {
                        mAudioTrack.play()
                    }
                }

            }
        }


        fun recalcAudioAux () {
            //Funcion que calcula todos los auxiliares para el audio

            //TODO Hacer algo que "resetee" los indices de los buffers en caso de que todos los
            // switchs estaban en OFF. Hacer un if para saber si estaban en OFF cosa de resetear los indices
            canCanales = sw_status.sum()
            dclvl = 2047 * canCanales

            if (canCanales != 0) {
                knorm = 16 / canCanales
            }
            else
            {
                knorm = 0
            }

        }


        val switch1: Switch = findViewById(R.id.switch_ch1)
        switch1.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[0] = 1
            } else {
                sw_status[0] = 0
            }
            recalcAudioAux()
        }

        val switch2: Switch = findViewById(R.id.switch_ch2)
        switch2.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[1] = 1
            } else {
                sw_status[1] = 0
            }
            recalcAudioAux()
        }
        val switch3: Switch = findViewById(R.id.switch_ch3)
        switch3.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[2] = 1
            } else {
                sw_status[2] = 0
            }
            recalcAudioAux()
        }

        val switch4: Switch = findViewById(R.id.switch_ch4)
        switch4.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[3] = 1
            } else {
                sw_status[3] = 0
            }
            recalcAudioAux()
        }
    }




    //Funcion de preparacion del audio
    private fun init_audio () {

        mAudioTrack.play()                 //mAudioTrack.stop();

        Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")

        var startTime = System.nanoTime()

        if (recepcion == true) {

        }
        else
        {
            /*
            mAudioTrack.write(bufSin1, 0, audio_chunk_sam, AudioTrack.WRITE_BLOCKING)        //Toma del buffer sinc nada mas

            last_Au = audio_chunk_sam                                       // Sincronizo los indices para que esten en el mismo lugar

             */
        }

        Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")
        Log.e("Measure", "TASK took write 3seg: " + ((System.nanoTime() - startTime) / 1000000) + "mS\n")
    }



    // Clase de recepcion --------------------------------------------------
    internal inner class rxRcv : AsyncTask<Void, Void, String>() {

        var hasInternet = false

        override fun doInBackground(vararg p0: Void?): String? {
            Process.setThreadPriority(-16)



            if (isNetworkAvailable()) {
                hasInternet = true

                //TODO Revisar usar resources en vez de harcodear el IP y puerto
                Log.e("Config Socket", getString(R.string.network_IP))
                val group = InetAddress.getByName("226.1.1.1")
                Log.e("Config Socket", getString(R.string.network_port))
                val s = MulticastSocket(4321)
                Log.e("Config Socket", "Uniendose al grupo")
                s.joinGroup(group)
                Log.e("Config Socket", "Socket OK - Esperando paquetes")

                // Cantidad de datos a recibir para que sea un audio_chunk
                // hoy se envian 120 muestras de cada canal, cada muestra son 2 bytes + 2 de señalizacion
                // 120 * 2 * 4 + 2

                val cantBytes = 120*2*4
                var auxContSam = 0

                while (power == 1) {
                    // La magia de la recepcion
                    datoCrudo = receive(s)
                    System.arraycopy(datoCrudo, 2, bufferRx, inBufferRx, cantBytes-2)


                    inBufferRx += cantBytes         //Indice buffer
                    inBufferRx %= bufferRx.size      //Buffer circular
                    auxContSam += muestrasEnvio

                    //TODO Aca esta la latencia, hay que rearmar esto pensando con emisor y receptor
                    if ( (auxContSam / audio_chunk_sam) >= 1)
                    {
                        for (i in 1 .. auxContSam/audio_chunk_sam) {
                            semProce.release()
                            auxContSam -= muestrasEnvio
                        }
                    }
                }
            }
            return "terminamo"

        }


        private fun isNetworkAvailable(): Boolean {
            val connectivityManager =
                    getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }


        fun receive(s: MulticastSocket):ByteArray {
            // get their responses!
            val buf = ByteArray(buff_recv * 2)
            val recv = DatagramPacket(buf, buf.size)
            s.receive(recv);
            return buf  //packetAsString
        }



    }


    //Funcion de creacion de ondas
    private fun createSinWaveBuffer(freq: Double, ms: Int, sampleRate: Int = samfreq): ShortArray {
        val samples = (ms * sampleRate / 1000)
        val output = ShortArray(samples)
        val period = sampleRate.toDouble() / freq
        for (i in output.indices) {
            val angle = 2.0 * Math.PI * i.toDouble() / period
            output[i] = ((sin(angle) * 2047f)+2047).toInt().toShort()
        }
        //output.forEach { println(it) }
        return output
    }

}



