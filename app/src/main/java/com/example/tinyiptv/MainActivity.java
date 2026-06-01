package com.example.tinyiptv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
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

    // Используем OpenDocument вместо GetContent для постоянного доступа
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, 
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        loadPlaylist(uri);
                    } catch (Exception e) {
                        loadPlaylist(uri); // Для старых версий
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String errorType = "Ошибка источника: ";
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) 
                    errorType = "HTTP запрещен (нужен HTTPS): ";
                
                Toast.makeText(MainActivity.this, errorType + error.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_url).setOnClickListener(v -> showUrlDialog());
        // Запрашиваем m3u файлы
        findViewById(R.id.btn_file).setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));

        initTouchLogic();

        String lastUri = prefs.getString("last_playlist", "");
        if (!lastUri.isEmpty()) {
            loadPlaylist(Uri.parse(lastUri));
        } else {
            drawer.openDrawer(GravityCompat.START);
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) {
                playChannel((currentIdx - 1 + channels.size()) % channels.size());
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                playChannel((currentIdx + 1) % channels.size());
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void loadPlaylist(Uri uri) {
        new Thread(() -> {
            List<String[]> tmp = new ArrayList<>();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line, name = "Канал";
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF")) {
                        int comma = line.lastIndexOf(",");
                        if (comma != -1) name = line.substring(comma + 1);
                    } else if (line.startsWith("http") || line.startsWith("rtsp") || line.startsWith("rtmp")) {
                        tmp.add(new String[]{name, line});
                    }
                }
                
                runOnUiThread(() -> {
                    channels = tmp;
                    updateList();
                    prefs.edit().putString("last_playlist", uri.toString()).apply();
                    if (!channels.isEmpty()) playChannel(prefs.getInt("last_idx", 0));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Файл недоступен или поврежден", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        currentIdx = idx % channels.size();
        player.setMediaItem(MediaItem.fromUri(channels.get(currentIdx)[1]));
        player.prepare();
        player.play();
        prefs.edit().putInt("last_idx", currentIdx).apply();
    }

    private void updateList() {
        ListView lv = findViewById(R.id.channel_list);
        List<String> names = new ArrayList<>();
        for (String[] c : channels) names.add(c[0]);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            playChannel(pos);
            drawer.closeDrawer(GravityCompat.START);
        });
    }

    private void initTouchLogic() {
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                if (vY > 0) playChannel((currentIdx - 1 + channels.size()) % channels.size());
                else playChannel((currentIdx + 1) % channels.size());
                return true;
            }
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (e.getX() < (float) playerView.getWidth() / 3) {
                    drawer.openDrawer(GravityCompat.START);
                    return true;
                }
                return false;
            }
        });
        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true; 
        });
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        new AlertDialog.Builder(this).setTitle("Ссылка на M3U").setView(input)
            .setPositiveButton("OK", (d, w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) loadPlaylist(Uri.parse(url));
            }).show();
    }

    @Override protected void onResume() { super.onResume(); hideSystemUI(); player.play(); }
    @Override protected void onStop() { super.onStop(); player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
