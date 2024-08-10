package com.tcc.leitordesensoresdetemperatura.fragments;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tcc.leitordesensoresdetemperatura.BuildConfig;
import com.tcc.leitordesensoresdetemperatura.R;

import java.io.IOException;
import java.util.Arrays;

import biblioteca.driver.UsbSerialDriver;
import biblioteca.driver.UsbSerialPort;
import biblioteca.driver.UsbSerialProber;
import biblioteca.util.CustomProber;
import biblioteca.util.HexDump;
import biblioteca.util.SerialInputOutputManager;

public class TelaTestes extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.BUILD_TYPE;
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private TextView textViewLinha1; // TextView para a linha 1

    private StringBuilder messageBuilder = new StringBuilder(); // StringBuilder para armazenar a mensagem recebida

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    public TelaTestes() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");

    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));
        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if (connected) {
            status("Disconectado");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_testes, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        textViewLinha1 = view.findViewById(R.id.textView1); // Referência ao TextView da linha 1
        //textViewLinha2 = view.findViewById(R.id.textView2); // Referência ao TextView da linha 2on
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());



        // Botão Enviar
        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TelaTestes.this.send(sendText.getText().toString());

            }
        });

        return view;
    }


    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.send_break) {
            if (!connected) {
                Toast.makeText(getActivity(), "Não Conectado", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // deve mostrar a barra de progresso em vez de bloquear o thread da UI
                    usbSerialPort.setBreak(false);
                    // implementação do método SpannableSrtingBuilder
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                } catch (UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK não é compatível", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getActivity(), "BREAK falhou: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {

                TelaTestes.this.receive(data);
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {
                TelaTestes.this.status("Conexão perdida: " + e.getMessage());
                TelaTestes.this.disconnect();
            }
        });
    }

    /*
     * Serial + UI
     */
    public void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("\n Conexao falhou: dispositivo não encontrado");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("Falha na conexão: nenhum driver para o dispositivo");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("Falha na conexão: portas insuficientes no dispositivo");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("Falha na conexão: permissão negada");
            else
                status("Falha na conexão: abrir conexão falhou");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("Conectado");
            Toast.makeText(getActivity(), "Conectado", Toast.LENGTH_SHORT).show();
            connected = true;
        } catch (Exception e) {
            status("Falha na conexão: " + e.getMessage());
            disconnect();
        }
    }

    public void disconnect() {
        connected = false;
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }

    void send(String str) {
        if(!connected) {
            Toast.makeText(getActivity(), "Não Conectado", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = (str).getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + data.length + " bytes\n");
            spn.append(HexDump.dumpHexString(data)).append("\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.setText(spn);
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
            textViewLinha1.setText("Enviou " + data.length + " bytes: " + HexDump.dumpHexString(data));
            textViewLinha1.setText(HexDump.dumpHexString(data));

        } catch (Exception e) {
            onRunError(e);
        }
    }

    public void read() {
        if (!connected) {
            Toast.makeText(getActivity(), "Não conectado", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            disconnect();
        }
    }

    public void receive(byte[] data) { //Criar botão de envio na tela testar [alterado p/ public]
        SpannableStringBuilder spn = new SpannableStringBuilder();
        spn.append(data.length + "\n");
        if(data.length > 0)
            spn.append(HexDump.dumpHexString(data)).append("\n");
        receiveText.append(spn);
    }

    public void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        receiveText.append(spn);
    }
}
