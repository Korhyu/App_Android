package com.example.therpsicore

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.os.AsyncTask
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Process
import android.os.Process.THREAD_PRIORITY_AUDIO
import android.util.Log
import android.widget.SeekBar
import android.widget.Switch
import androidx.annotation.RequiresApi
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.stream.IntStream
import kotlin.concurrent.thread
import kotlin.math.sin




class AudioActivity : AppCompatActivity() {

    private val RxMaxBuf=120*16*10
    private val power = 1
    private var bufferRx = ByteArray(48100)
    private var bufferPlay = ShortArray(RxMaxBuf)
    private var prueba = ByteArray(999)
    private var outBufferRx:Int=0
    private var inBufferRx:Int=0
    private var countBufferRx:Int=0
    private var inBufferPlay:Int=0
    private var outBufferPlay:Int=0
    private var countBufferPlay:Int=0
    private var check=12

    var datoCrudo=ByteArray(500*2)
    var total=0
    var recp=0
    var plays=0
    var finalTime=0
    var carga=0


    // Datos del audio
    var buff_recv = 481
    var bloque_datos = 800                          // 1 periodo de la de menor frecuencia son 80 muestras
    var ultima_copia = 0
    private var samfreq = 40000
    private var audiobuffer = 2000
    private var sincbuffsize = 120


    var mAudioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC, samfreq, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, audiobuffer,
            AudioTrack.MODE_STREAM
    )

    private val bufSin1 = createSinWaveBuffer(500.0, 3000)
    private val bufSin2 = createSinWaveBuffer(1000.0, 3000)
    private val bufSin3 = createSinWaveBuffer(5000.0, 3000)
    private val bufSin4 = createSinWaveBuffer(10000.0, 3000)


    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        mAudioTrack.play();                 //mAudioTrack.stop();
        Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")
        var startTime = System.nanoTime()
        mAudioTrack.write(bufSin1, 0, bufSin1.size,AudioTrack.WRITE_BLOCKING)
        Log.e("ERROR", "stado atrack ${mAudioTrack.state} y ${AudioTrack.PLAYSTATE_PLAYING}\n")

        Log.e("Measure", "TASK took write 3seg: " + ((System.nanoTime() - startTime) / 1000000) + "mS\n")
        rxRcv().execute()


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




    }



    // Clase de recepcion --------------------------------------------------
    internal inner class rxRcv : AsyncTask<Void, Void, String>() {

        var hasInternet = false
        var cant_canales = 4                //Inicializo en 4 porque descuento los canales apagados (al principio los 4 apagados // )
        var sw_status = arrayOf(0,0,0,0)    //Estado de los switchs para evitar el uso de funciones mas complejas
        var vol_status = arrayOf(0,0,0,0)   //Estado de los sliders de volumen




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

            }
            return "terminamo"

        }


        private fun isNetworkAvailable(): Boolean {
            val connectivityManager =
                    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }



        fun receive(s: MulticastSocket):ByteArray {
            // get their responses!
            val buf = ByteArray(buff_recv*2)
            val recv = DatagramPacket(buf, buf.size)
            s.receive(recv);
            return buf  //packetAsString
        }

        //Funcion que toma el estado de los switchs y pone las muestras correspondientes en el buffer de reproduccion
        fun addAudioTrack() {

            bufferPlay[inBufferPlay] = 0

            //Variable aux para normalizacion con cantidad de canales activos
            var norm = sw_status.sum()
            var dclvl = 2048 * norm
            var aux = 0

            aux += bufferRx[outBufferRx+0] * sw_status[0]           //Canal 1
            aux += bufferRx[outBufferRx+1] * sw_status[1]           //Canal 2
            aux += bufferRx[outBufferRx+2] * sw_status[2]           //Canal 3
            aux += bufferRx[outBufferRx+3] * sw_status[3]           //Canal 4

            //TODO Hablar con matias por el tema de la potencia que le falta a la ecuacion
            bufferPlay[inBufferPlay]= ((aux-dclvl)*4).toShort()
        }


        //Funcion que lee los switches y escribe las variables auxiliares para manejar los datos y volumen
        /*fun switchStatus() {
            //TODO Revisar TODA esta funcion a ver si funciona quizas haya que modificarla para que
            // los switches actualicen el estado usando onCheckedChanged
            // https://stackoverflow.com/questions/10576307/android-how-do-i-correctly-get-the-value-from-a-switch

            val switch1 = (Switch) findViewById(R.id.switch_ch1)
            val switch2 = (Switch) findViewById(R.id.switch_ch2)
            val switch3 = (Switch) findViewById(R.id.switch_ch3)
            val switch4 = (Switch) findViewById(R.id.switch_ch4)

            /*
            val volslider1 = (SeekBar) findViewById(R.id.vol_ch1)
            val volslider2 = (SeekBar) findViewById(R.id.vol_ch2)
            val volslider3 = (SeekBar) findViewById(R.id.vol_ch3)
            val volslider4 = (SeekBar) findViewById(R.id.vol_ch4)
            */

            //Canal 1
            if (switch1.isCheked())
                sw_status[0] = 1
            else
                sw_status[0] = 0

            //Canal 2
            if (switch2.isCheked())
                sw_status[1] = 1
            else
                sw_status[1] = 0

            //Canal 3
            if (switch3.isCheked())
                sw_status[2] = 1
            else
                sw_status[2] = 0

            //Canal 4
            if (switch4.isCheked())
                sw_status[3] = 1
            else
                sw_status[3] = 0
        }*/

        //TODO Revisar hacer una clase para verificar el estado de los seekbar
        fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
            vol_status[0] = p1
        }

    }





    //Funcion de creacion de ondas
    private fun createSinWaveBuffer(freq: Double, ms: Int, sampleRate: Int = samfreq): ShortArray {
        val samples = (ms * sampleRate / 1000)
        val output = ShortArray(samples)
        val period = sampleRate.toDouble() / freq
        for (i in output.indices) {
            val angle = 2.0 * Math.PI * i.toDouble() / period
            output[i] = ((sin(angle) * 2047f)+2047).toShort()
        }
        //output.forEach { println(it) }
        return output
    }

}



