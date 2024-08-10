package com.tcc.leitordesensoresdetemperatura.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;

import com.tcc.leitordesensoresdetemperatura.R;

import java.util.ArrayList;

import biblioteca.driver.UsbSerialDriver;
import biblioteca.driver.UsbSerialProber;
import biblioteca.util.CustomProber;

public class TelaPrincipal extends ListFragment {

    static class ListItem {
        UsbDevice device;
        int port;
        UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    private final ArrayList<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 2400;
    private boolean withIoManager = true;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        listAdapter = new ArrayAdapter<ListItem>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                ListItem item = listItems.get(position);
                if (view == null)
                   view = getActivity().getLayoutInflater().inflate(R.layout.fragment_tela_principal, parent, false);

                //Botão para abrir a Tela Monitora
                View monitoraBtn = view.findViewById(R.id.btnMonitora);
                monitoraBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ListItem item = listItems.get(0);//Pegar o item 0
                        if(item.driver == null) {
                            Toast.makeText(getActivity(), "Nenhum driver", Toast.LENGTH_SHORT).show();
                        } else {
                            Bundle args = new Bundle();
                            args.putInt("device", item.device.getDeviceId());
                            args.putInt("port", item.port);
                            args.putInt("baud", baudRate);
                            args.putBoolean("withIoManager", withIoManager);
                            Fragment fragment = new Monitora();
                            fragment.setArguments(args);
                            getFragmentManager().beginTransaction().replace(R.id.container, fragment, "Monitora").addToBackStack(null).commit();
                        }
                    }
                });

                //Botão para abrir a Tela Configura Equipamento
                View configuraBtn = view.findViewById(R.id.btnConfigura);
                configuraBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        //TerminalFragment.this.read();
                        ListItem item = listItems.get(0);//Pegar o item 0
                        if(item.driver == null) {
                            Toast.makeText(getActivity(), "Nenhum driver", Toast.LENGTH_SHORT).show();
                        } else {
                            Bundle args = new Bundle();
                            args.putInt("device", item.device.getDeviceId());
                            args.putInt("port", item.port);
                            args.putInt("baud", baudRate);
                            args.putBoolean("withIoManager", withIoManager);
                            Fragment fragment = new ConfiguraEquipamento();
                            fragment.setArguments(args);
                            getFragmentManager().beginTransaction().replace(R.id.container, fragment, "Monitora").addToBackStack(null).commit();
                        }
                    }
                });

                //Botão para abrir a Tela Download
                View downloadBtn = view.findViewById(R.id.btnDownload);
                downloadBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        //TerminalFragment.this.read();
                        ListItem item = listItems.get(0);//Pegar o item 0
                        if(item.driver == null) {
                            Toast.makeText(getActivity(), "Nenhum driver", Toast.LENGTH_SHORT).show();
                        } else {
                            Bundle args = new Bundle();
                            args.putInt("device", item.device.getDeviceId());
                            args.putInt("port", item.port);
                            args.putInt("baud", baudRate);
                            args.putBoolean("withIoManager", withIoManager);
                            Fragment fragment = new Download();
                            fragment.setArguments(args);
                            getFragmentManager().beginTransaction().replace(R.id.container, fragment, "Monitora").addToBackStack(null).commit();
                        }
                    }
                });

                //Botão para abrir a Tela de Teste
                View btnTelaTeste = view.findViewById(R.id.btnTelaTeste);
                btnTelaTeste.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        //TerminalFragment.this.read();
                        ListItem item = listItems.get(0);//Pegar o item 0
                        if(item.driver == null) {
                            Toast.makeText(getActivity(), "Nenhum driver", Toast.LENGTH_SHORT).show();
                        } else {
                            Bundle args = new Bundle();
                            args.putInt("device", item.device.getDeviceId());
                            args.putInt("port", item.port);
                            args.putInt("baud", baudRate);
                            args.putBoolean("withIoManager", withIoManager);
                            TelaTestes fragment = new TelaTestes();
                            fragment.setArguments(args);
                            getFragmentManager().beginTransaction().replace(R.id.container, fragment, "Monitora").addToBackStack(null).commit();
                        }
                    }
                });

                //view.setVisibility(View.GONE);
                return view;
            }//getView
        }; //listAdapter
    } //oCreate

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setEmptyText("[Nenhum dispositivo USB encontrado]");
        ((TextView) getListView().getEmptyView()).setTextSize(20);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        Toast.makeText(getActivity(), "OnResume", Toast.LENGTH_SHORT).show();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if(id == R.id.refresh) {
            refresh();
            return true;
        }
        else if (id ==R.id.read_mode) {
            final String[] values = getResources().getStringArray(R.array.read_modes);
            int pos = withIoManager ? 0 : 1; // read_modes[0]=event/io-manager, read_modes[1]=direct
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Modo de Leitura");
            AlertDialog.Builder builder1 = builder.setSingleChoiceItems(values,pos,new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            withIoManager = (which == 0);
                            dialog.dismiss();
                        }
                    });
            builder.create().show();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    void refresh() {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();
        for(UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if(driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if(driver != null) {
                for(int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new ListItem(device, port, driver));
            } else {
                listItems.add(new ListItem(device, 0, null));
            }
        }
        listAdapter.notifyDataSetChanged();

    }

    public void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');

    }

}
