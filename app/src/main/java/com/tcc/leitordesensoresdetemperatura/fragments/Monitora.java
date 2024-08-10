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
import android.widget.CheckBox;
import android.widget.CompoundButton;
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

import biblioteca.driver.UsbSerialDriver;
import biblioteca.driver.UsbSerialPort;
import biblioteca.driver.UsbSerialProber;
import biblioteca.util.CustomProber;
import biblioteca.util.HexDump;
import biblioteca.util.SerialInputOutputManager;

public class Monitora extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final int REQUEST_CODE_PERMISSIONS = 123;
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.BUILD_TYPE;
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;
    private int deviceId, portNum, baudRate;
    private boolean withIoManager;
    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;

    private CheckBox checkBoxMostrar;
    private  CheckBox  checkBoxGrava;
    private TextView receiveText;
    private TextView textSensor1, textSensor2, textSensor3, textSensor4;

    private StringBuilder messageBuilder = new StringBuilder();

    // Variável global para acumular valores recebidos
    private StringBuilder acumulaValores = new StringBuilder();

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    public Monitora() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
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
        if(usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if(connected) {
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
        View view = inflater.inflate(R.layout.fragment_monitora, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        // definir como cor padrão para reduzir o número de vãos
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        textSensor1 = view.findViewById(R.id.sensor1);
        textSensor2 = view.findViewById(R.id.sensor2);
        textSensor3 = view.findViewById(R.id.sensor3);
        textSensor4 = view.findViewById(R.id.sensor4);

        // CheckBox Mostrar, se tiver habilitada irá exibir os valores monitorados senão
        // seguirá o monitoramento porém sem exibir os valores no sensores.
        checkBoxMostrar = (CheckBox)view.findViewById(R.id.checkbox_1);
        checkBoxMostrar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(checkBoxMostrar.isChecked()){
                    textSensor1.setVisibility(View.VISIBLE);
                    textSensor2.setVisibility(View.VISIBLE);
                    textSensor3.setVisibility(View.VISIBLE);
                    textSensor4.setVisibility(View.VISIBLE);
                }
                else {
                    textSensor1.setVisibility(View.INVISIBLE);
                    textSensor2.setVisibility(View.INVISIBLE);
                    textSensor3.setVisibility(View.INVISIBLE);
                    textSensor4.setVisibility(View.INVISIBLE);
                }

            }
        });
        checkBoxGrava = view.findViewById(R.id.checkbox_2);


        // Botão Monitorar
        View btnMonitora = view.findViewById(R.id.btn_Monitora);
        btnMonitora.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String monitora = "B";
                Monitora.this.send(monitora);
            }
        });

        // Botão Monitorar e Grava
        View btnMonitGrava = view.findViewById(R.id.btnMonitGrava);
        btnMonitGrava.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                    String monit_grava = "C";
                    send(monit_grava);
                }

        });

        //Botão Parar de Monitorar
        View btnParar = view.findViewById(R.id.btnParar);
        btnParar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                usbIoManager.stop();
                String parar = "D";
                send(parar);
                // Salva os dados acumulados em um arquivo
                if (checkBoxGrava.isChecked()) {
                    saveToFile();
                }

                // Limpa a variável acumulaValores após salvar os dados
                acumulaValores.setLength(0);

                if (checkBoxGrava.isChecked()) {
                    Toast.makeText(getActivity(), "Monitoramento parado e dados salvos!", Toast.LENGTH_SHORT).show();
                }

            }
        });

        // Botão para fechar Tela Monitorar e voltar à Tela Principal
        View btnFechar = view.findViewById(R.id.btnFechar2);
        btnFechar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                getFragmentManager().popBackStack();
            }
        });

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    getContext().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissão concedida
            } else {
                // Permissão negada
                Toast.makeText(getContext(), "Permissão necessária para salvar arquivos", Toast.LENGTH_SHORT).show();
            }
        }
    }


    private void saveToFile() {
        // Verifica se o armazenamento externo está disponível para leitura e escrita
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            // Define o diretório onde os dados serão salvos
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DadosSensores");
            if (!dir.exists()) {
                dir.mkdirs(); // Cria o diretório se não existir
            }

            // Define o nome do arquivo
            File file = new File(dir, "dados_sensores.txt");
            try {
                // Abre um FileOutputStream para escrever no arquivo
                FileOutputStream fos = new FileOutputStream(file);
                // Converte os dados acumulados em uma string e escreve no arquivo
                String dataToSave = acumulaValores.toString();
                fos.write(dataToSave.getBytes());
                fos.close();

                if (checkBoxGrava.isChecked()) {
                    Toast.makeText(getActivity(), "Dados gravados em: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                }
            } catch (IOException e) {
                Toast.makeText(getActivity(), "Erro ao gravar dados: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(getActivity(), "Armazenamento externo não está disponível", Toast.LENGTH_LONG).show();
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if( id == R.id.send_break) {
            if(!connected) {
                Toast.makeText(getActivity(), "Não Conectado", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // deve mostrar a barra de progresso em vez de bloquear o thread da UI
                    usbSerialPort.setBreak(false);
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.blackColor)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    //receiveText.append(spn);
                } catch(UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK não é compatível", Toast.LENGTH_SHORT).show();
                } catch(Exception e) {
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
                Monitora.this.receive(data);
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {
                Monitora.this.status("Conexão perdida: " + e.getMessage());
                Monitora.this.disconnect();
            }
        });
    }

    /*
     * Serial + UI
     */
    public void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("\n Conexao falhou: dispositivo não encontrado");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("Falha na conexão: nenhum driver para o dispositivo");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("Falha na conexão: portas insuficientes no dispositivo");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("Falha na conexão: permissão negada");
            else
                status("Falha na conexão: falha na abertura");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try{
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            }catch (UnsupportedOperationException e){
                status("Parâmetros de configuração não suportados");
            }
            if(withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("Conectado");
            Toast.makeText(getActivity(), "Conectado", Toast.LENGTH_SHORT).show();
            connected = true;
        } catch (Exception e) {
            status("Falha na conexão: ");
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if(usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
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
            receiveText.setText("Enviou " + data.length + " bytes: " + HexDump.dumpHexString(data));
            receiveText.setText(HexDump.dumpHexString(data));

        } catch (Exception e) {
            onRunError(e);
        }
    }

    private int linhaAtual = 0; // Variável para rastrear a linha atual
    private static final int LINHA_INICIAL = 5; // Define a linha inicial (0-indexado, ou seja, terceira linha)

    public void receive(byte[] data) {
        for (byte b : data) {
            char c = (char) b;
            messageBuilder.append(c); // Adiciona o caractere ao StringBuilder
            if (c == '\n') { // Verifica se a mensagem está completa
                linhaAtual++; // Incrementa a linha atual

                if (linhaAtual >= LINHA_INICIAL) { // Verifica se estamos na linha ou além da linha inicial
                    String message = messageBuilder.toString();
                    String[] parts = message.split(","); // Divide a mensagem em partes separadas por vírgula

                    // Exibe cada parte separada no botão correspondente.
                    int num_sensor = 0;
                    for (String part : parts) {
                        num_sensor++;
                        switch(num_sensor) {
                            case 1:
                                textSensor1.setText(part);
                                break;
                            case 2:
                                textSensor2.setText(part);
                                break;
                            case 3:
                                textSensor3.setText(part);
                                break;
                            case 4:
                                textSensor4.setText(part);
                                break;
                            default:
                                //erro se chegar aqui.
                        }//switch
                    }
                    if (checkBoxGrava != null && checkBoxGrava.isChecked()) {
                        acumulaValores.append(message);
                    }

                }

                messageBuilder.setLength(0); // Limpa o StringBuilder para a próxima mensagem
            }
        }
    }
    public void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        receiveText.append(spn);
    }
}
