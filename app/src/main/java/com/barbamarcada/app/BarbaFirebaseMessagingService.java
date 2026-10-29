package com.barbamarcada.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/** Recebe mensagens FCM enquanto o app está em primeiro plano e cria notificações Android. */
public final class BarbaFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "barba_agendamentos";
    private static final String ALLOWED_HOST = "barbamarcada.infinityfree.me";

    @Override
    public void onNewToken(String token) {
        // A Activity sincroniza o token com o PHP usando a sessão autenticada da WebView.
        // Não fazemos requisição sem a sessão/cookie do barbeiro.
        getSharedPreferences("barba_marcada_preferences", MODE_PRIVATE)
                .edit().putString("latest_fcm_token", token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        RemoteMessage.Notification remoteNotification = message.getNotification();
        String title = remoteNotification != null ? remoteNotification.getTitle() : data.get("title");
        String body = remoteNotification != null ? remoteNotification.getBody() : data.get("body");
        if (title == null || title.trim().isEmpty()) title = "Barba Marcada";
        if (body == null || body.trim().isEmpty()) body = "Você tem uma atualização na agenda.";

        String target = data.get("url");
        if (!isSafeTarget(target)) target = "https://" + ALLOWED_HOST + "/admin/index.php";

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setAction("com.barbamarcada.app.OPEN_ADMIN_NOTIFICATION");
        openApp.putExtra("push_url", target);
        openApp.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, target.hashCode(), openApp, pendingFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setColor(Color.rgb(17, 24, 39))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setContentIntent(contentIntent)
                .build();

        String tag = data.get("tag");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            if (tag == null || tag.isEmpty()) manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
            else manager.notify(tag, 0, notification);
        }
    }

    private boolean isSafeTarget(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        Uri uri = Uri.parse(raw);
        return "https".equalsIgnoreCase(uri.getScheme())
                && ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
}
