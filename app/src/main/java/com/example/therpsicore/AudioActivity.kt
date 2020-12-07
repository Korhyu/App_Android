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
import android.widget.Button
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
    private var ountBufferRx:Int=0
    private var inBufferPlay:Int=0
    private var outBufferPlay:Int=0
    private var countBufferPlay:Int=0
    private var check=12


    // hoy se envian 120 muestras de cada canal, cada muestra son 2 bytes + 2 de señalizacion
    // 120 * 2 * 4 + 2
    val cantBytes = 120*2*4
    var datoCrudo=ByteArray(2000)
    var contpaquetes = 0                                //Contador de paquetes redibidos
    var contsamprep = 0                                 //Contador de muestras reproducidas
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
    private var audiobuffer = 120
    private var sincbuffsize = 120
    private val muestrasEnvio = 120


    //Variables y valores auxiliares
    var buff_recv = 480
    var audio_chunk_ms = 10                                     // Bloque de audio a escribir en el sink expresado en ms
    val audio_chunk_sam = audio_chunk_ms * (samfreq/1000)       // Audio chunk expresado en muestras
    val recepcion = true                                       // Cambiar esto a true cuando se reciba desde la BBB
    var bufSin_index = audio_chunk_sam                          // Arranco a contar despues de la primer copia en inicializacion


    //Buffers
    private var bufferRx = ByteArray(buff_recv*1000)          //ByteArray(buff_recv*1000)
    private var bufferProcesado = ShortArray(audio_chunk_sam)
    private var audio_chunk = ShortArray(bufferRx.size / 8)
    private var audio_ch1 = IntArray(bufferRx.size / 8)
    private var audio_ch2 = IntArray(bufferRx.size / 8)
    private var audio_ch3 = IntArray(bufferRx.size / 8)
    private var audio_ch4 = IntArray(bufferRx.size / 8)



    // Buffers de tonos auxiliares
    private val bufSin1 = createSinWaveBuffer(500.0, 3000)
    private val bufSin2 = createSinWaveBuffer(1000.0, 3000)
    private val bufSin3 = createSinWaveBuffer(1500.0, 3000)
    private val bufSin4 = createSinWaveBuffer(2000.0, 3000)


    //Estado de los switches y seekbars
    var sw_status = arrayOf(0, 0, 0, 0)    //Estado de los switchs para evitar el uso de funciones mas complejas
    var sb_volumen = arrayOf(0, 0, 0, 0)       //Estado de los sliders de volumen


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



    @ExperimentalUnsignedTypes
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        //TODO Boton play/stop colorcito, funcion cambio de color y texto
        //TODO estetica de la app


        init_audio()

//        mAudioTrack.write(bufSin2, 0, 3*samfreq, AudioTrack.WRITE_BLOCKING)
//        Thread.sleep(3000)

        rxRcv().execute()


        // Pasa del bufRx a los diferentes canales
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Procesamiento") {
            while(power==1) {

                var aux = 0

                semProce.acquireUninterruptibly()                                   //Espero esten listos los datos desde la recepcion

                for (i in last_Pr until (last_Pr+cantBytes) step 8) {
                    audio_ch1[i/8] = ((bufferRx[i + 0].toUByte().toInt() + (bufferRx[i + 1].toInt() shl 8)))
                    audio_ch2[i/8] = ((bufferRx[i + 2].toUByte().toInt() + (bufferRx[i + 3].toInt() shl 8)))
                    audio_ch3[i/8] = ((bufferRx[i + 4].toUByte().toInt() + (bufferRx[i + 5].toInt() shl 8)))
                    audio_ch4[i/8] = ((bufferRx[i + 6].toUByte().toInt() + (bufferRx[i + 7].toInt() shl 8)))


                    //TODO agregar el volumen
                    aux += audio_ch1[i/8] * sw_status[0]          //Canal 1
                    aux += audio_ch2[i/8] * sw_status[1]          //Canal 2
                    aux += audio_ch3[i/8] * sw_status[2]          //Canal 3
                    aux += audio_ch4[i/8] * sw_status[3]          //Canal 4


                    audio_chunk[i/8] = ((aux - dclvl) * knorm).toShort()
                    aux = 0
                }

                semChunk.release()

                last_Pr += cantBytes
                last_Pr %= bufferRx.size
            }
        }

        // Thread que pasa de los buffers de canales al audio sink
        // Ademas afecta por los switchs y volumen
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Envio") {
            var lastAudioSink = 0
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

                    // Mando al audio sink
                    mAudioTrack.write(audio_chunk, lastAudioSink, cantBytes/8, AudioTrack.WRITE_BLOCKING)
                    contsamprep ++
                    lastAudioSink += cantBytes/8
                    lastAudioSink %= audio_chunk.size

                    if(mAudioTrack.playState == PLAYSTATE_STOPPED)
                    {
                        mAudioTrack.play()
                    }
                }

            }
        }


        thread(start = true, priority = 10, name = "Informacion") {
            while (power == 1) {
                //Thread de log e informacion

                //Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")
                Log.i("Paquetes", "Paquetes recibidos ${contpaquetes}\n")
                Log.i("Paquetes", "Paquetes reproducidos  ${contsamprep}\n")
                Log.i("Semaforos", "Semaforo Proc ${semProce}")
                Log.i("Semaforos", "Semaforo Chunk ${semChunk}")
                //Log.i("Informacion", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")
                //Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")
                //Log.e("Measure", "TASK took write 3seg: " + ((System.nanoTime() - startTime) / 1000000) + "mS\n")

                Thread.sleep(1000)
            }

            }


        val playstop: Button = findViewById(R.id.button_ss)
        playstop.setOnClickListener {
            if(mAudioTrack.playState == PLAYSTATE_STOPPED)
            {
                //Hay que darle play
                mAudioTrack.play()
                //playstop.background("")
            }
            else
            {
                //Hay que darle stop
                mAudioTrack.stop()
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



        //TODO Agregar los metodos para ver si los seekbars cambian de estado.
    }


    private fun recalcAudioAux () {
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

    //Funcion de preparacion del audio
    private fun init_audio () {

        //Pongo el switch1 en encendido y la barra de volumen al 50%

        var switch1: Switch = findViewById(R.id.switch_ch1)
        switch1.toggle()

        /*
        if (switch1.isChecked())
        {
            sw_status[0] = 1
            recalcAudioAux()
        }
        */


        mAudioTrack.play()                 //mAudioTrack.stop();

    }







    // Clase de recepcion --------------------------------------------------
    internal inner class rxRcv : AsyncTask<Void, Void, String>() {

        var hasInternet = false

        override fun doInBackground(vararg p0: Void?): String? {
            Process.setThreadPriority(-16)



            if (isNetworkAvailable()) {
                hasInternet = true

                //TODO Revisar usar resources en vez de harcodear el IP y puerto
                Log.i("Config Socket", getString(R.string.network_IP))
                val group = InetAddress.getByName("226.1.1.1")
                Log.i("Config Socket", getString(R.string.network_port))
                val s = MulticastSocket(4321)
                Log.i("Config Socket", "Uniendose al grupo")
                s.joinGroup(group)
                Log.i("Config Socket", "Socket OK - Esperando paquetes")

                // Cantidad de datos a recibir para que sea un audio_chunk
                // hoy se envian 120 muestras de cada canal, cada muestra son 2 bytes + 2 de señalizacion
                // 120 * 2 * 4 + 2

                while (power == 1) {
                    // La magia de la recepcion
                    datoCrudo = receive(s)

                    //TODO hacer discrtiminacion de los mensajes si falta alguno en el contador copiar anterior
                    //TODO pensar que pasa si llegan paquetes en diferente orden
                    System.arraycopy(datoCrudo, 2, bufferRx, inBufferRx, cantBytes)

                    inBufferRx += cantBytes         //Indice buffer
                    inBufferRx %= bufferRx.size      //Buffer circular

                    contpaquetes++
                    semProce.release()
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
            val buf = ByteArray(481 * 2)
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



