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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tcc.leitordesensoresdetemperatura.BuildConfig;
import com.tcc.leitordesensoresdetemperatura.R;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import biblioteca.driver.UsbSerialDriver;
import biblioteca.driver.UsbSerialPort;
import biblioteca.driver.UsbSerialProber;
import biblioteca.util.CustomProber;
import biblioteca.util.HexDump;
import biblioteca.util.SerialInputOutputManager;

public class ConfiguraEquipamento extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.BUILD_TYPE;
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;
    private int deviceId, portNum, baudRate;
    private boolean withIoManager;
    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private Spinner spinner1, spinner2;
    private Button btnNumSensores, btnTempAmostra, btnLerConfig;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    // Variável para armazenar os valores do spinner2
    private Map<String, Integer> spinner2Values;

    public ConfiguraEquipamento() {
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
        View view = inflater.inflate(R.layout.fragment_cofig_equip, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        spinner1 = view.findViewById(R.id.spinner1);
        spinner2 = view.findViewById(R.id.spinner2);

        // Carregar o array do resources para o spinner1
        ArrayAdapter<CharSequence> adapterSpinner1 = ArrayAdapter.createFromResource(getActivity(),
                R.array.itensSpinner1, android.R.layout.simple_spinner_item); // Supondo que você tenha itensSpinner1 no strings.xml
        adapterSpinner1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner1.setAdapter(adapterSpinner1);

        // Carregar o array do resources para o spinner2
        ArrayAdapter<CharSequence> adapterSpinner2 = ArrayAdapter.createFromResource(getActivity(),
                R.array.itensSpinner2, android.R.layout.simple_spinner_item);
        adapterSpinner2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner2.setAdapter(adapterSpinner2);

        // Botão para configurar o número de Sensores no Equipamento
        btnNumSensores = view.findViewById(R.id.btnNumSensores);
        btnNumSensores.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int selectedIndex = spinner1.getSelectedItemPosition();
                String comando = "H" + (selectedIndex + 1);
                send(comando);
            }
        });

        // Botão Tempo de amostra para configurar o inervalo de tempo das amostras
        btnTempAmostra = view.findViewById(R.id.btnTemp);
        btnTempAmostra.setOnClickListener(new View.OnClickListener() {
            @Override
          public void onClick(View v) {
                int selectedIndex = spinner2.getSelectedItemPosition();
                String strValor="";

                switch (selectedIndex) {
                    case 0:
                        strValor = "0018";
                        break;
                    case 1:
                        strValor = "0180";
                        break;
                    case 2:
                        strValor = "0540";
                        break;
                    case 3:
                        strValor = "1080";
                        break;
                    case 4:
                        strValor = "10800";
                        break;
                    default:
                        Toast.makeText(getContext(), "Valor selecionado inválido", Toast.LENGTH_SHORT).show();
                        return; // Sai do método sem enviar comando
                }//fim switch
                String comando = "J" + strValor;
                send(comando);
            }
        });

        // Botão Ler Configurações exibe as configurações atuais do equipamento
        btnLerConfig = view.findViewById(R.id.btnLerConfig);
        btnLerConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("F");
                String numerosLinha1 = lerSerial();
                // Convertendo numerosLinha1 para inteiro e definindo a seleção no spinner1
                try {
                    int valorLinha1 = Integer.parseInt(numerosLinha1.trim());
                    setIndex(spinner1, getIndexForSpinner1(valorLinha1));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }

                send("G");
                String numerosLinha2 = lerSerial();
                // Processar valores de numerosLinha2
                List<String> valores = Arrays.asList(numerosLinha2.split("\\s+")); // Assumindo que os valores são separados por espaço
                for (String valor : valores) {
                    try {
                        int valorNumerico = Integer.parseInt(valor.trim());
                        double valorDividido = valorNumerico / 18.0;
                        int index = getIndexForSpinner2((int) valorDividido);
                        setIndex(spinner2, index);
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        // Botão para fechar Tela Monitorar e voltar à Tela Principal
        View btnFechar = view.findViewById(R.id.btnSair);
        btnFechar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFragmentManager().popBackStack();
            }
        });

        return view;
    }

    // Método para obter o índice para spinner1 com base no valor
    private int getIndexForSpinner1(int valor) {
        switch (valor) {
            case 1:
                return 0;
            case 2:
                return 1;
            case 3:
                return 2;
            case 4:
                return 3;
            // Adicione mais casos conforme necessário
            default:
                return -1; // Valor padrão, pode ser tratado conforme necessário
        }
    }

    // Método para obter o índice para spinner2 com base no valor dividido
    private int getIndexForSpinner2(int valorDividido) {
        switch (valorDividido) {
            case 1:
                return 0;
            case 10:
                return 1;
            case 30:
                return 2;
            case 60:
                return 3;
            case 600:
                return 4;
            case 1800:
                return 5;
            case 3600:
                return 6;
            default:
                return -1; // Valor padrão, pode ser tratado conforme necessário
        }
    }

    // Método para definir o índice do Spinner
    private void setIndex(Spinner spinner, int index) {
        if (index >= 0 && index < spinner.getCount()) {
            spinner.setSelection(index);
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

        return str.toString().replaceAll("[^0-9]", "");
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

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {

            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(new Runnable() {
            @Override
            public void run() {
                ConfiguraEquipamento.this.status("Conexão perdida: " + e.getMessage());
                ConfiguraEquipamento.this.disconnect();
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

    public void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        receiveText.append(spn);
    }
}
