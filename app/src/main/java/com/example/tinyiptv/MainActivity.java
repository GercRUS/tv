package com.example.tinyiptv;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import java.io.*;
import java.net.URL;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private DrawerLayout drawer;
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        findViewById(R.id.btn_settings).setOnClickListener(v -> showUrlDialog());
        setupGestures();
        String lastUrl = prefs.getString("url", "");
        if(!lastUrl.isEmpty()) loadPlaylist(lastUrl);
    }

    private void loadPlaylist(String urlPath) {
        new Thread(() -> {
            try {
                Scanner s = new Scanner(new URL(urlPath).openStream());
                List<String[]> tmp = new ArrayList<>();
                while (s.hasNextLine()) {
                    String line = s.nextLine();
                    if (line.startsWith("#EXTINF")) {
                        String name = line.substring(line.lastIndexOf(",") + 1);
                        if (s.hasNextLine()) tmp.add(new String[]{name, s.nextLine()});
                    }
                }
                runOnUiThread(() -> {
                    channels = tmp;
                    updateList();
                    playChannel(prefs.getInt("last_idx", 0));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка плейлиста", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void playChannel(int idx) {
        if (channels.isEmpty() || idx >= channels.size()) return;
        currentIdx = idx;
        player.setMediaItem(MediaItem.fromUri(channels.get(idx)[1]));
        player.prepare(); player.play();
        prefs.edit().putInt("last_idx", idx).apply();
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
                if (vY > 0) playChannel((currentIdx + 1) % channels.size());
                else playChannel((currentIdx - 1 + channels.size()) % channels.size());
                return true;
            }
        });
        playerView.setOnTouchListener((v, event) -> gd.onTouchEvent(event));
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        input.setText(prefs.getString("url", ""));
        new AlertDialog.Builder(this).setTitle("M3U URL").setView(input).setPositiveButton("OK", (d, w) -> {
            String url = input.getText().toString();
            prefs.edit().putString("url", url).apply();
            loadPlaylist(url);
        }).show();
    }

    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
