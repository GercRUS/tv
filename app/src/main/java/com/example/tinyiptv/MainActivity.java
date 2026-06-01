package com.example.tinyiptv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private DrawerLayout drawer;
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;

    private final ActivityResultLauncher<String> filePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) loadPlaylist(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        findViewById(R.id.btn_url).setOnClickListener(v -> showUrlDialog());
        findViewById(R.id.btn_file).setOnClickListener(v -> filePicker.launch("*/*"));

        setupGestures();

        String lastPath = prefs.getString("last_playlist", "");
        if (!lastPath.isEmpty()) {
            loadPlaylist(Uri.parse(lastPath));
        } else {
            drawer.openDrawer(Gravity.LEFT); // Сразу открываем меню, если пусто
        }
    }

    private void loadPlaylist(Uri uri) {
        prefs.edit().putString("last_playlist", uri.toString()).apply();
        new Thread(() -> {
            List<String[]> tmp = new ArrayList<>();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line, name = "Unknown";
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF")) {
                        int comma = line.lastIndexOf(",");
                        name = (comma != -1) ? line.substring(comma + 1) : "Channel";
                    } else if (line.startsWith("http") || line.startsWith("rtmp")) {
                        tmp.add(new String[]{name, line});
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
            
            runOnUiThread(() -> {
                channels = tmp;
                updateList();
                if (!channels.isEmpty()) playChannel(prefs.getInt("last_idx", 0));
            });
        }).start();
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        currentIdx = (idx < 0 || idx >= channels.size()) ? 0 : idx;
        player.setMediaItem(MediaItem.fromUri(channels.get(currentIdx)[1]));
        player.prepare();
        player.play();
        prefs.edit().putInt("last_idx", currentIdx).apply();
    }

    private void updateList() {
        List<String> names = new ArrayList<>();
        for (String[] c : channels) names.add(c[0]);
        ListView lv = findViewById(R.id.channel_list);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((p, v, pos, id) -> { playChannel(pos); drawer.closeDrawers(); });
    }

    private void setupGestures() {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                if (Math.abs(vY) > Math.abs(vX)) {
                    playChannel((vY > 0) ? (currentIdx + 1) % channels.size() : (currentIdx - 1 + channels.size()) % channels.size());
                    return true;
                }
                return false;
            }
        });
        playerView.setOnTouchListener((v, event) -> gd.onTouchEvent(event));
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        new AlertDialog.Builder(this).setTitle("URL плейлиста").setView(input)
            .setPositiveButton("OK", (d, w) -> loadPlaylist(Uri.parse(input.getText().toString()))).show();
    }

    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
