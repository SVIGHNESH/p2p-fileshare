package com.p2p.android.ui;
import com.p2p.android.R;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.p2p.android.AppState;

public class SettingsFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        AppState state = AppState.get(requireContext());

        EditText hostField = view.findViewById(R.id.tracker_host_field);
        EditText portField = view.findViewById(R.id.tracker_port_field);
        EditText nameField = view.findViewById(R.id.display_name_field);
        Button saveBtn     = view.findViewById(R.id.save_btn);

        // Pre-fill with saved values
        hostField.setText(state.trackerHost);
        portField.setText(String.valueOf(state.trackerPort));
        nameField.setText(state.myDisplayName.get());

        saveBtn.setOnClickListener(v -> {
            String host = hostField.getText().toString().trim();
            String portStr = portField.getText().toString().trim();
            String name = nameField.getText().toString().trim();

            state.trackerHost = host;
            state.trackerPort = portStr.isEmpty() ? 9000 : Integer.parseInt(portStr);
            if (!name.isEmpty()) state.myDisplayName.set(name);

            if (state.trackerClient != null && !host.isEmpty()) {
                state.trackerClient.setTracker(host, state.trackerPort);
                new Thread(() -> {
                    boolean ok = state.trackerClient.register(
                            state.myIp,
                            com.p2p.core.protocol.Protocol.DEFAULT_PEER_PORT,
                            state.sharedFiles);
                    state.isConnected = ok;
                    requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(),
                                ok ? "Connected to tracker!" : "Could not connect to tracker",
                                Toast.LENGTH_SHORT).show()
                    );
                }).start();
            }

            state.savePrefs();
            saveBtn.setText("Saved!");
            view.postDelayed(() -> saveBtn.setText("Save Settings"), 2000);
        });
    }
}
