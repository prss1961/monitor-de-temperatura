package com.tcc.leitordesensoresdetemperatura.fragments;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tcc.leitordesensoresdetemperatura.BuildConfig;
import com.tcc.leitordesensoresdetemperatura.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import biblioteca.driver.UsbSerialDriver;
import biblioteca.driver.UsbSerialPort;
import biblioteca.driver.UsbSerialProber;
import biblioteca.util.CustomProber;
import biblioteca.util.HexDump;
import biblioteca.util.SerialInputOutputManager;

public class Download extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final int REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION = 1;
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.BUILD_TYPE;
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;
    private int deviceId, portNum, baudRate;
    private boolean withIoManager;
    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private Button btnDownload;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;
    private String receivedData;
    private TextView receiveText;
    private EditText nomeArquivo;
    private String fileName;

    public Download() {
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
        withIoManager = false;
        // Solicita permissão de escrita no armazenamento externo se necessário
        requestStoragePermission();
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
            status(" ");
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
        View view = inflater.inflate(R.layout.fragment_download, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        nomeArquivo = view.findViewById(R.id.down_Nome_Arquivo);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());


        // Botão Download - realiza a transferência dos dados gravados no equipamento
        btnDownload = view.findViewById(R.id.btn_Download);
        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String download = "E";
                Download.this.send(download);
                receivedData = lerSerial();
                receiveText.setText(receivedData);

                // Obter o nome do arquivo do EditText
                String userInputFileName = nomeArquivo.getText().toString().trim();
                if (!userInputFileName.isEmpty()) {
                    fileName = userInputFileName + ".txt";
                    saveToFile(receivedData);
                } else {
                    Toast.makeText(getActivity(), "Por favor, insira o nome do arquivo", Toast.LENGTH_SHORT).show();
                }
            }
        });


        // Botão para fechar Tela Download e voltar à Tela Principal
        View btnFechar = view.findViewById(R.id.btnFechar3);
        btnFechar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFragmentManager().popBackStack();
            }
        });

        return view;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissão concedida
                Toast.makeText(getActivity(), "Permissão para escrever no concedida", Toast.LENGTH_SHORT).show();
            } else {
                // Permissão negada
                //Toast.makeText(getActivity(), "Permissão para escrever no armazenamento negada", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveToFile(String data) {
        // Definindo o diretório de destino
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DadosMonitorados");
        if (!dir.exists()) {
            dir.mkdirs(); // Cria a pasta se não existir
        }

        // Definindo o arquivo
        File file = new File(dir, fileName);

        OutputStream outputStream = null;
        // Salvando os dados no arquivo
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data.getBytes());
            fos.write("\n".getBytes()); // Adiciona uma nova linha após cada conjunto de dados
            Toast.makeText(getActivity(), "Dados salvos em " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity(), "Erro ao salvar dados: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // Handle the exception
                }
            }
        }
    }

   private String lerSerial() {
        byte[] buffer = new byte[8192];
        StringBuilder str = new StringBuilder();
        int len;

        try {
            while ((len = usbSerialPort.read(buffer, READ_WAIT_MILLIS)) > 0) {
                String data = new String(buffer, 0, len);
                str.append(data);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return str.toString();
    }

    void send(String str) {
        if (!connected) {
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
            receiveText.setText("Enviou " + data.length + " bytes: " + HexDump.dumpHexString(data));
            receiveText.setText(HexDump.dumpHexString(data));
        } catch (Exception e) {
            onRunError(e);
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText(" ");
            return true;
        } else if (id == R.id.send_break) {
            if (!connected) {
                Toast.makeText(getActivity(), "Não Conectado", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // deve mostrar a barra de progresso em vez de bloquear o thread da UI
                    usbSerialPort.setBreak(false);
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


   private void connect() {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbDevice device = null;
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
                status("Falha na conexão: falha na abertura");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("Parâmetros de configuração não suportados");
            }
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status(" ");
            Toast.makeText(getActivity(), "Conectado", Toast.LENGTH_SHORT).show();
            connected = true;
        } catch (Exception e) {
            status("Falha na conexão: ");
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    /*
     * SerialInputOutputManager.Listener
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {
                receiveText.setText(receivedData);
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {
                Download.this.status("Conexão perdida: " + e.getMessage());
                Download.this.disconnect();
            }
        });
    }

    public void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        receiveText.append(spn);
    }
}
