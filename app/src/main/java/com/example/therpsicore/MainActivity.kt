package com.example.therpsicore

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.Thread.sleep


val DEBUG = true

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button_prueba = findViewById<Button>(R.id.button_prueba)
        val checkBox_red = findViewById<CheckBox>(R.id.checkBox_red)
        var appEncendida = false
        var dialogshowed = false

        while (!appEncendida) {
            when (requestLocationPermission()) {
                MainActivity.PERMISSION_CODE_ACCEPTED -> getWifiSSID()
            }

            if (checkBox_red.isChecked or DEBUG) {
                //sleep(1000)
                val intent = Intent(this, AudioActivity::class.java)
                appEncendida = true
                startActivity(intent)
            } else {
                if (!dialogshowed) {
                    openDialog()
                    dialogshowed = true
                }
            }
        }
    }


//TODO revisar cada 2s el wifi para ver si cambio la red y acceder


    // Verificacion del WIFI -----------------------------------------------------------------------
    companion object {
        const val PERMISSION_CODE_ACCEPTED = 1
        const val PERMISSION_CODE_NOT_AVAILABLE = 0
    }

    private fun requestLocationPermission(): Int {
        if (ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            } else {
                // request permission
                ActivityCompat.requestPermissions(this,
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                        MainActivity.PERMISSION_CODE_ACCEPTED)
            }
        } else {
            // already granted
            return MainActivity.PERMISSION_CODE_ACCEPTED
        }

        // not available
        return MainActivity.PERMISSION_CODE_NOT_AVAILABLE
    }

    private fun getWifiSSID() {
        val mWifiManager: WifiManager = (this.getApplicationContext().getSystemService(Context.WIFI_SERVICE) as WifiManager)!!
        val info: WifiInfo = mWifiManager.getConnectionInfo()

        val ssid_valida = getString(R.string.network_name)
        val checkBox_red = findViewById<CheckBox>(R.id.checkBox_red)

        if (info.getSupplicantState() === SupplicantState.COMPLETED) {
            val ssid: String = info.getSSID()
            Log.d("wifi name", ssid)

            if(ssid.equals(ssid_valida))
            {
                //La SSID esta bien puedo pasar a la proxima etapa
                checkBox_red.isChecked = true;
            }

        } else {
            Log.d("wifi name", "could not obtain the wifi name")

            openDialog()
        }
    }


    // Dialogo de Error de WIFI --------------------------------------------------------------------
    private fun openDialog(){
        val dialogo = AlertDialog.Builder(this)
        dialogo.setTitle("Conexion WIFI ausente")
        dialogo.setMessage(
                //TODO Ver si podemos usar los recursos del string
                "Por favor conectese a la red correspondiende" +
                        "\nNombre:  linksis     Pass: xXxXxXxX" +
                        "\n\nLa contraseña es sin espacios y sin comillas. " +
                        "Por favor loguee en la red correspondiente para recibir el streaming"

            /*
            "Por favor conectese a la red correspondiende" +
                        "\nNombre: "+ R.string.network_name + "\nPass: " + R.string.network_pass +
                        "\n\nLa contraseña es sin espacios y sin comillas. " +
                        "Por favor loguee en la red correspondiente para recibir el streaming"
             */

        )
        dialogo.setNeutralButton("Ok", { dialogInterface: DialogInterface, i: Int -> })
        dialogo.show()
    }
}