package com.p2p.android.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.p2p.android.AppState;
import com.p2p.core.protocol.Protocol.FileInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class SharedFragment extends Fragment {

    private static final int PICK_FILE_REQUEST = 1;
    private LinearLayout filesContainer;
    private TextView emptyText;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_shared, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        filesContainer = view.findViewById(R.id.files_container);
        emptyText      = view.findViewById(R.id.empty_text);
        TextView folderPath = view.findViewById(R.id.folder_path_text);
        Button addBtn = view.findViewById(R.id.add_file_btn);

        AppState state = AppState.get(requireContext());
        folderPath.setText("Shared folder: " + state.sharedFolderPath);

        addBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select a file to share"), PICK_FILE_REQUEST);
        });

        renderFiles();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderFiles();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != PICK_FILE_REQUEST || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        AppState state = AppState.get(requireContext());
        try {
            String name = getFileName(uri);
            File dest = new File(state.getSharedFolder(), name);
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            state.refreshSharedFiles();
            renderFiles();
            Toast.makeText(requireContext(), "\"" + name + "\" added to sharing!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not add file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void renderFiles() {
        if (filesContainer == null) return;
        filesContainer.removeAllViews();
        AppState state = AppState.get(requireContext());

        if (state.sharedFiles.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        emptyText.setVisibility(View.GONE);
        for (FileInfo fi : state.sharedFiles) {
            filesContainer.addView(buildFileRow(fi));
        }
    }

    private View buildFileRow(FileInfo fi) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(28, 24, 28, 24);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 12);
        row.setLayoutParams(lp);
        row.setBackgroundResource(R.drawable.card_background);

        LinearLayout meta = new LinearLayout(requireContext());
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        meta.setLayoutParams(metaLp);

        TextView name = new TextView(requireContext());
        name.setText(fi.name);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        TextView details = new TextView(requireContext());
        details.setText(formatSize(fi.size) + "  ·  Shared ✓");
        details.setTextColor(0xFF4CAF50);
        details.setTextSize(12);

        meta.addView(name);
        meta.addView(details);

        Button removeBtn = new Button(requireContext());
        removeBtn.setText("Remove");
        removeBtn.setTextColor(0xFFF44336);
        removeBtn.setBackgroundColor(0x22F44336);
        removeBtn.setOnClickListener(v -> {
            File f = new File(AppState.get(requireContext()).getSharedFolder(), fi.name);
            if (f.delete()) {
                AppState.get(requireContext()).refreshSharedFiles();
                renderFiles();
                Toast.makeText(requireContext(), "Removed from sharing", Toast.LENGTH_SHORT).show();
            }
        });

        row.addView(meta);
        row.addView(removeBtn);
        return row;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (col >= 0) result = cursor.getString(col);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : -1;
            if (cut >= 0) result = result.substring(cut + 1);
        }
        return result != null ? result : "file_" + System.currentTimeMillis();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
