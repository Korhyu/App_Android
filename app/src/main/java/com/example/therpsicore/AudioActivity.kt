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
import kotlin.math.pow
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
    var last_RX = 0                                     // Ultimo dato del buffer Rx enviado
    var last_Pr = 0                                     // Ultimo dato del buffer Procesado enviado


    // Datos del audio
    private var samfreq = 40000
    private var audiobuffer = 2000
    private var sincbuffsize = 120


    //Variables y valores auxiliares
    var buff_recv = 481
    var audio_chunk_ms = 10                                     // Bloque de audio a escribir en el sink expresado en ms
    val audio_chunk_sam = audio_chunk_ms * (samfreq/1000)       // Audio chunk expresado en muestras
    val recepcion = false                                       // Cambiar esto a true cuando se reciva desde la BBB
    var bufSin_index = audio_chunk_sam                          // Arranco a contar despues de la primer copia en inicializacion


    //Buffers
    private var bufferRx = ByteArray(48100)
    private var bufferProcesado = ShortArray(audio_chunk_sam)
    private var audio_chunk = ShortArray(audio_chunk_sam)
    private var audio_ch1 = ShortArray(audio_chunk_sam)
    private var audio_ch2 = ShortArray(audio_chunk_sam)
    private var audio_ch3 = ShortArray(audio_chunk_sam)
    private var audio_ch4 = ShortArray(audio_chunk_sam)



    // Buffers de tonos auxiliares
    private val bufSin1 = createSinWaveBuffer(500.0, 3000)
    private val bufSin2 = createSinWaveBuffer(1000.0, 3000)
    private val bufSin3 = createSinWaveBuffer(2000.0, 3000)
    private val bufSin4 = createSinWaveBuffer(4000.0, 3000)


    //Estado de los switches y seekbars
    var sw_status = arrayOf(0, 0, 0, 0)    //Estado de los switchs para evitar el uso de funciones mas complejas
    var sb_vol1 = arrayOf(0, 0, 0, 0)       //Estado de los sliders de volumen


    //Semaforos
    private val semEnvio: Semaphore = Semaphore(1, false)



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

        // Thread que atiende el buffer de audio
        // Pasa del bufProc al audioSink
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Control Audio") {
            while(power==1) {

                if (recepcion == true) {
                    for (i in 0 until audio_chunk_sam step 8) {
                        audio_ch1[i] = ((bufferRx[last_RX + i + 0].toUByte().toInt() + (bufferRx[last_RX + i + 1].toInt() shl 8)) - 2047).toShort()
                        audio_ch2[i] = ((bufferRx[last_RX + i + 2].toUByte().toInt() + (bufferRx[last_RX + i + 3].toInt() shl 8)) - 2047).toShort()
                        audio_ch3[i] = ((bufferRx[last_RX + i + 4].toUByte().toInt() + (bufferRx[last_RX + i + 5].toInt() shl 8)) - 2047).toShort()
                        audio_ch4[i] = ((bufferRx[last_RX + i + 6].toUByte().toInt() + (bufferRx[last_RX + i + 7].toInt() shl 8)) - 2047).toShort()
                    }

                    last_RX += audio_chunk_sam
                    last_RX %= bufferRx.size
                }
                else
                {
                    // Demora que simula la recepcion de datos nuevos, ese "-1" hace que luego
                    // de varias reproducciones genere latencia en el encendido y apagaoo de los switchs
                    Thread.sleep(audio_chunk_ms.toLong()-1)
                }

                semEnvio.release()
            }
        }

        // Thread que atiende la recepcion de datos y la separa en canales
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Procesamiento y envio de Audio") {
            while(power==1) {

                semEnvio.acquireUninterruptibly()                          //Pido el semaforo de envio, si no esta libre es que no hay datos para enviar al sink

                var canCanales = sw_status.sum()
                var dclvl = 2047 * canCanales
                var knorm = 0
                var aux = 0

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
                    // Este if determina si estamos usando el buffer interno o la recepcion externa
                    if (recepcion == true) {

                        for (i in 0 until (audio_chunk_sam-1)) {

                            aux += audio_ch1[last_Pr + i] * sw_status[0]          //Canal 1
                            aux += audio_ch2[last_Pr + i] * sw_status[1]          //Canal 2
                            aux += audio_ch3[last_Pr + i] * sw_status[2]          //Canal 3
                            aux += audio_ch4[last_Pr + i] * sw_status[3]          //Canal 4


                            audio_chunk[i] = ((aux - dclvl) * knorm).toShort()
                            aux = 0
                        }

                    }
                    else
                    {
                        // Este pedazo de codigo es para cuando se usan los buffers internos


                        knorm = 16 / canCanales

                        for (i in 0 until (audio_chunk_sam-1)) {
//                            aux += bufSin1[last_Pr + i] * sw_status[0] * sb_vol[0]         //Canal 1
//                            aux += bufSin2[last_Pr + i] * sw_status[1] * sb_vol[1]         //Canal 2
//                            aux += bufSin3[last_Pr + i] * sw_status[2] * sb_vol[2]         //Canal 3
//                            aux += bufSin4[last_Pr + i] * sw_status[3] * sb_vol[3]         //Canal 4

                            aux += bufSin1[last_Pr + i] * sw_status[0]          //Canal 1
                            aux += bufSin2[last_Pr + i] * sw_status[1]          //Canal 2
                            aux += bufSin3[last_Pr + i] * sw_status[2]          //Canal 3
                            aux += bufSin4[last_Pr + i] * sw_status[3]          //Canal 4


                            audio_chunk[i] = ((aux - dclvl) * knorm).toShort()
                            aux = 0
                        }

                        if(mAudioTrack.playState == PLAYSTATE_STOPPED)
                        {
                            mAudioTrack.play()
                        }

                    }

                    last_Pr += audio_chunk_sam
                    last_Pr %= bufSin1.size


                    // Mando al audio sink
                    mAudioTrack.write(audio_chunk, 0, audio_chunk_sam, AudioTrack.WRITE_BLOCKING)
                }

            }
        }

        val switch1: Switch = findViewById(R.id.switch_ch1)
        switch1.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[0] = 1
            } else {
                sw_status[0] = 0
            }
        }

        val switch2: Switch = findViewById(R.id.switch_ch2)
        switch2.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[1] = 1
            } else {
                sw_status[1] = 0
            }
        }
        val switch3: Switch = findViewById(R.id.switch_ch3)
        switch3.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[2] = 1
            } else {
                sw_status[2] = 0
            }
        }

        val switch4: Switch = findViewById(R.id.switch_ch4)
        switch4.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sw_status[3] = 1
            } else {
                sw_status[3] = 0
            }
        }






            //Funcion que lee los switches y escribe las variables auxiliares para manejar los datos y volumen
        /*fun switchStatus() {
            //TODO Revisar TODA esta funcion a ver si funciona quizas haya que modificarla para que
            // los switches actualicen el estado usando onCheckedChanged
            // https://stackoverflow.com/questions/10576307/android-how-do-i-correctly-get-the-value-from-a-switch


            /*
            val volslider1 = (SeekBar) findViewById(R.id.vol_ch1)
            val volslider2 = (SeekBar) findViewById(R.id.vol_ch2)
            val volslider3 = (SeekBar) findViewById(R.id.vol_ch3)
            val volslider4 = (SeekBar) findViewById(R.id.vol_ch4)
            */

         */


        /*
        //Thread de reproduccion de audio
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Reproduccion Audio") { //THREAD_PRIORITY_AUDIO
            while(power==1){
                if(recp>0) {
                    System.arraycopy(bufSin1, 0, bufferPlay, inBufferPlay, sincbuffsize)
                    inBufferPlay += sincbuffsize
                    inBufferPlay %= RxMaxBuf

                    //Funcion Jose
                    //Si tomamos el primer byte y lo multiplicamos por sus pesos y despues el segundo byte y hacemos lo mismo y despues sumamos el resultado sera mas eficiente?

                    //Funcion Mati
    //                    for (index in outBufferRx+2..(buff_recv * 2 - 2) step 8) {
    //                        bufferPlay[inBufferPlay] = ((bufferRx[index].toUByte().toInt() + (bufferRx[index+1].toInt() shl 8))- 2047).toShort()
    //                           // ((datoCrudo[index].toInt() + (datoCrudo[index + 1].toInt() shl 8))- 2047).toShort()  // ((((datoCrudo[index].toShort() + (datoCrudo[index + 1].toInt() shl 8)).toShort()) - 2047) * 16).toShort()
    //                        inBufferPlay++
    //                        inBufferPlay %= RxMaxBuf
    //                    }
                    outBufferRx += buff_recv*2
                    outBufferRx %= buff_recv*10
                    recp--
                    countBufferPlay++

                }
            }
        }

        /*
        //Super thread Jose TM
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Super Thread Jose"){
            while(power==1){
                if( ultima_copia < (outBufferPlay + bloque_datos) )
                {
                    System.arraycopy(bufSin1, 0, bufferPlay, inBufferPlay, bloque_datos)
                    ultima_copia += bloque_datos
                    ultima_copia %= RxMaxBuf

                    Thread.sleep(2)
                }
                else
                {
                    Thread.sleep(1)
                }
            }
        }

         */

        //Hilo que toma los datos del Buffer y los reproduce. La fucion Write es bloqueante a si que este Hilo solo se encarga de esto.
        thread(start = true, priority = THREAD_PRIORITY_AUDIO, name = "Paso de Datos"){ //THREAD_PRIORITY_AUDIO
            while(power==1){
                if(countBufferPlay>=9) {
//                    var startTime = System.nanoTime()
                    mAudioTrack.write(bufferPlay, outBufferPlay, bloque_datos, AudioTrack.WRITE_BLOCKING)
//                    Log.e("Measure", "TASK took nada " + ((System.nanoTime() - startTime) / 1000000) + "mS\n")
                    plays++
                    outBufferPlay += bloque_datos
                    outBufferPlay %= RxMaxBuf
                    countBufferPlay -= 8

                }
//                else{Log.e("FAlTA BUFF PLAY", "$countBufferPlay \n")}
            }
        }
        */
    }




    //Funcion de preparacion del audio
    private fun init_audio () {

        mAudioTrack.play()                 //mAudioTrack.stop();

        Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")

        var startTime = System.nanoTime()

        if (recepcion == true) {
            //TODO manejo interno desde el buffer de recepcion al audio sink
        }
        else
        {
            /*
            mAudioTrack.write(bufSin1, 0, audio_chunk_sam, AudioTrack.WRITE_BLOCKING)        //Toma del buffer sinc nada mas

            last_Pr = audio_chunk_sam                                       // Sincronizo los indices para que esten en el mismo lugar

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
            var auxSin=0
            if (isNetworkAvailable()) {
                hasInternet = true

                //TODO Revisar usar resources en vez de harcodear el IP y puerto
                //Log.e("Config Socket", getString(R.string.network_IP))
                val group = InetAddress.getByName("226.1.1.1")
                //Log.e("Config Socket", getString(R.string.network_port))
                val s = MulticastSocket(4321)
                Log.e("Config Socket", "Uniendose al grupo")
                s.joinGroup(group)
                Log.e("Config Socket", "Socket OK - Esperando paquetes")



                /*
                while (power == 1) {
//                    if (countBufferRx < 10) {
//                        var startTime = System.nanoTime()
//                        Log.e("Measure", "TASK took nada " + ((System.nanoTime() - startTime) / 1000000) + "mS\n")

                    //datoCrudo = receive(s)
                    System.arraycopy(datoCrudo, 0, bufferRx, inBufferRx, buff_recv * 2)
                    Thread.sleep(((sincbuffsize*1000)/samfreq).toLong())

                    recp++
                    total++
                    inBufferRx+=buff_recv*2         //Indice buffer
                    inBufferRx %= buff_recv*10      //Buffer circular
                }

                 */



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



