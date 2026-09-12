package com.kez.gps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

// Без POI imports - ползваме вграден Java SAX + ZipFile
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Константи
    private static final int FILE_REQUEST = 1;
    private static final int SPEECH_REQUEST = 2;
    private static final int PERM_REQUEST = 3;

    // Файлово търсене — търси тези имена в Downloads и Documents
    private static final String[] EXCEL_NAMES = {
        "GPS_koordinati_na_klientite_na_KEZ_Karlovo.xlsx",
        "GPS_koordinati_na_klientite_na_KEZ_Karlovo.xls",
        "GPS_koordinati.xlsx",
        "GPS_koordinati.xls",
        "gps.xlsx",
        "gps.xls",
        "kez_gps.xlsx",
        "kez_gps.xls"
    };

    // UI
    private MapView map;
    private EditText itnInput;
    private TextView statusText;
    private LinearLayout resultsPanel;
    private RecyclerView resultsList;
    private FrameLayout loadingOverlay;
    private TextView loadingText;
    private Button btnMap, btnSat;

    // Данни
    private Map<String, GpsRecord> gpsData = new HashMap<>();
    private List<GpsRecord> foundRecords = new ArrayList<>();
    private List<Marker> markers = new ArrayList<>();
    private Polyline routeLine;
    private boolean usingSatellite = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // OSMDroid конфигурация
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        // Инициализирай UI
        map = findViewById(R.id.map);
        itnInput = findViewById(R.id.itn_input);
        statusText = findViewById(R.id.status_text);
        resultsPanel = findViewById(R.id.results_panel);
        resultsList = findViewById(R.id.results_list);
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingText = findViewById(R.id.loading_text);
        btnMap = findViewById(R.id.btn_map);
        btnSat = findViewById(R.id.btn_sat);

        // Настрой карта
        setupMap();

        // Hint цвят на полето
        itnInput.setHintTextColor(Color.parseColor("#888888"));

        // Бутони
        findViewById(R.id.search_btn).setOnClickListener(v -> search());
        findViewById(R.id.mic_btn).setOnClickListener(v -> startVoice());
        findViewById(R.id.btn_load).setOnClickListener(v -> pickExcelFile());
        findViewById(R.id.btn_gmaps).setOnClickListener(v -> openGoogleMaps());
        findViewById(R.id.btn_close).setOnClickListener(v -> resultsPanel.setVisibility(View.GONE));
        btnMap.setOnClickListener(v -> setMapType(false));
        btnSat.setOnClickListener(v -> setMapType(true));

        // RecyclerView
        resultsList.setLayoutManager(new LinearLayoutManager(this));

        // Клавиш Enter в полето
        itnInput.setOnEditorActionListener((v, actionId, event) -> { search(); return true; });

        // Поискай разрешения
        requestPermissions();

        // Опитай автоматично зареждане на Excel
        autoLoadExcel();
    }

    private void setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(11.0);
        map.getController().setCenter(new GeoPoint(42.64, 24.85));
    }

    private void setMapType(boolean satellite) {
        usingSatellite = satellite;
        if (satellite) {
            // Google сателит
            org.osmdroid.tileprovider.tilesource.XYTileSource googleSat =
                new org.osmdroid.tileprovider.tilesource.XYTileSource(
                    "Google-Sat",
                    0, 20, 256, ".png",
                    new String[]{"https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                                 "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"}
                );
            map.setTileSource(googleSat);
            btnSat.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#e94560")));
            btnSat.setTextColor(Color.WHITE);
            btnMap.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0f3460")));
            btnMap.setTextColor(Color.parseColor("#aaaaaa"));
        } else {
            map.setTileSource(TileSourceFactory.MAPNIK);
            btnMap.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#e94560")));
            btnMap.setTextColor(Color.WHITE);
            btnSat.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0f3460")));
            btnSat.setTextColor(Color.parseColor("#aaaaaa"));
        }
        map.invalidate();
    }

    // Автоматично търси Excel файла в известни локации
    private void autoLoadExcel() {
        new Thread(() -> {
            File found = findExcelFile();
            if (found != null) {
                final File f = found;
                runOnUiThread(() -> loadExcelFile(f));
            } else {
                runOnUiThread(() -> statusText.setText("Зареди Excel файла"));
            }
        }).start();
    }

    private File findExcelFile() {
        // Търси в Downloads, Documents, и root на external storage
        File[] searchDirs = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStorageDirectory(),
            new File(Environment.getExternalStorageDirectory(), "GPS"),
            new File(Environment.getExternalStorageDirectory(), "KEZ"),
        };

        for (File dir : searchDirs) {
            if (dir == null || !dir.exists()) continue;
            for (String name : EXCEL_NAMES) {
                File candidate = new File(dir, name);
                if (candidate.exists() && candidate.canRead()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // Зареди Excel файл
    private void loadExcelFile(File file) {
        showLoading("Зареждане на " + file.getName() + "...");
        new Thread(() -> {
            try {
                InputStream is = new FileInputStream(file);
                loadFromStream(is, file.getName());
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideLoading();
                    Toast.makeText(this, "Грешка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ================================================================
    // ЛЕКОТЕЖЕСТ xlsx четец - без POI зареждане в RAM
    // Чете директно ZIP → XML с Java SAX
    // ================================================================

    private void loadFromStream(InputStream is, String filename) throws Exception {
        File tmpFile = File.createTempFile("kez_excel", ".tmp", getCacheDir());
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile);
            byte[] buf = new byte[65536];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            fos.close();
            is.close();

            runOnUiThread(() -> loadingText.setText("Анализиране..."));

            // Провери формат
            boolean isXlsx = isXlsxFormat(tmpFile);
            if (isXlsx) {
                loadXlsxLightweight(tmpFile, filename);
            } else {
                throw new Exception("Файлът е в стар .xls формат.\nМоля конвертирай го в .xlsx:\nExcel → Запази като → .xlsx");
            }
        } finally {
            tmpFile.delete();
        }
    }

    // Провери дали е истински .xlsx
    private boolean isXlsxFormat(File file) {
        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file);
            boolean ok = zip.getEntry("[Content_Types].xml") != null;
            zip.close();
            return ok;
        } catch (Exception e) { return false; }
    }

    // Лек четец на .xlsx - зарежда Shared Strings като stream, после чете sheet
    private void loadXlsxLightweight(File file, String filename) throws Exception {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file);
        try {
            // Стъпка 1: Зареди Shared Strings като ArrayList (не XSSFSharedStringsTable)
            runOnUiThread(() -> loadingText.setText("Зареждане на стрингове..."));
            loadSharedStrings(zip); // зарежда в SQLite на диска

            // Стъпка 2: Намери sheet1
            java.util.zip.ZipEntry sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml");
            if (sheetEntry == null) {
                // Опитай да намериш първия лист
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry e = entries.nextElement();
                    if (e.getName().startsWith("xl/worksheets/sheet") && e.getName().endsWith(".xml")) {
                        sheetEntry = e;
                        break;
                    }
                }
            }
            if (sheetEntry == null) throw new Exception("Не намирам лист в Excel файла");

            // Стъпка 3: Парсирай sheet с лек SAX handler
            runOnUiThread(() -> loadingText.setText("Четене на данните..."));
            InputStream sheetStream = zip.getInputStream(sheetEntry);
            LightSheetHandler handler = new LightSheetHandler(filename);
            javax.xml.parsers.SAXParserFactory factory = javax.xml.parsers.SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            javax.xml.parsers.SAXParser parser = factory.newSAXParser();
            parser.parse(sheetStream, handler);
            sheetStream.close();
        } finally {
            zip.close();
        }
    }

    // Зареди Shared Strings като прост ArrayList - само стрингове, минимална памет
    // SQLite база за Shared Strings на диска (не в RAM)
    private android.database.sqlite.SQLiteDatabase ssDb = null;
    private File ssDbFile = null;

    private void openSsDb() throws Exception {
        // НЕ изтриваме файла - SQLite се нуждае от него
        ssDbFile = new File(getCacheDir(), "kez_ss_" + System.currentTimeMillis() + ".db");
        ssDbFile.getParentFile().mkdirs();
        ssDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(ssDbFile.getAbsolutePath(), null);
        ssDb.execSQL("CREATE TABLE IF NOT EXISTS ss (i INTEGER PRIMARY KEY, v TEXT)");
        ssDb.execSQL("PRAGMA synchronous=OFF");
        ssDb.execSQL("PRAGMA journal_mode=MEMORY");
        ssDb.execSQL("PRAGMA cache_size=1000");
    }

    private void closeSsDb() {
        try { if (ssDb!=null) ssDb.close(); } catch (Exception e) {}
        try { if (ssDbFile!=null) ssDbFile.delete(); } catch (Exception e) {}
        ssDb = null; ssDbFile = null;
    }

    private String getSs(int idx) {
        if (ssDb==null||!ssDb.isOpen()) return "";
        try (android.database.Cursor c = ssDb.rawQuery(
                "SELECT v FROM ss WHERE i=?", new String[]{String.valueOf(idx)})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    private List<String> loadSharedStrings(java.util.zip.ZipFile zip) throws Exception {
        java.util.zip.ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return new ArrayList<>();

        openSsDb();
        InputStream is = zip.getInputStream(entry);
        javax.xml.parsers.SAXParserFactory factory = javax.xml.parsers.SAXParserFactory.newInstance();
        javax.xml.parsers.SAXParser parser = factory.newSAXParser();
        final int[] idx = {0};

        ssDb.beginTransaction();
        try {
            parser.parse(is, new org.xml.sax.helpers.DefaultHandler() {
                boolean inT = false;
                final StringBuilder sb = new StringBuilder();
                final android.content.ContentValues cv = new android.content.ContentValues();
                @Override public void startElement(String u, String l, String n, org.xml.sax.Attributes a) {
                    if ("t".equals(n)) { inT=true; sb.setLength(0); }
                    else if ("si".equals(n)) sb.setLength(0);
                }
                @Override public void characters(char[] ch, int s, int len) {
                    if (inT) sb.append(ch, s, len);
                }
                @Override public void endElement(String u, String l, String n) {
                    if ("t".equals(n)) inT=false;
                    else if ("si".equals(n)) {
                        cv.put("i", idx[0]++); cv.put("v", sb.toString());
                        ssDb.insert("ss", null, cv);
                    }
                }
            });
            ssDb.setTransactionSuccessful();
        } finally { ssDb.endTransaction(); }
        is.close();
        // Върни null - сигнал че ползваме DB
        return null;
    }

    // Лек SAX handler за sheet данните
    class LightSheetHandler extends org.xml.sax.helpers.DefaultHandler {
        private final String filename;
        private boolean inV = false, isStr = false;
        private StringBuilder val = new StringBuilder();
        private List<String> curRow = new ArrayList<>();
        private List<String> headers = new ArrayList<>();
        private int rowNum = 0, headerRow = -1;
        private int colITN = -1, colLat = -1, colLon = -1;
        private int[] latCnt = new int[100], lonCnt = new int[100];
        private int scanRows = 0;
        private int colIdx = 0;
        private final Map<String, GpsRecord> data = new HashMap<>();

        LightSheetHandler(String filename) {
            this.filename = filename;
        }

        private int colLetterToIndex(String ref) {
            int col = 0;
            for (char c : ref.toCharArray()) {
                if (!Character.isLetter(c)) break;
                col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
            }
            return col - 1;
        }

        @Override
        public void startElement(String u, String l, String name, org.xml.sax.Attributes a) {
            if ("row".equals(name)) { curRow.clear(); colIdx = 0; }
            else if ("c".equals(name)) {
                String r = a.getValue("r");
                colIdx = (r != null) ? colLetterToIndex(r) : colIdx + 1;
                isStr = "s".equals(a.getValue("t"));
                val.setLength(0); inV = false;
                while (curRow.size() <= colIdx) curRow.add("");
            }
            else if ("v".equals(name) || "t".equals(name)) { inV = true; val.setLength(0); }
        }

        @Override
        public void characters(char[] ch, int s, int len) {
            if (inV) val.append(ch, s, len);
        }

        @Override
        public void endElement(String u, String l, String name) {
            if (("v".equals(name) || "t".equals(name)) && inV) {
                inV = false;
                String v = val.toString();
                if (isStr) {
                    try { v = getSs(Integer.parseInt(v)); }
                    catch (Exception e) { v = ""; }
                }
                while (curRow.size() <= colIdx) curRow.add("");
                curRow.set(colIdx, v);
            } else if ("row".equals(name)) {
                processRow();
                rowNum++;
            }
        }

        private void processRow() {
            if (curRow.isEmpty()) return;

            if (headerRow < 0) {
                for (int c = 0; c < curRow.size(); c++) {
                    if ("ИТН".equalsIgnoreCase(curRow.get(c).trim())) {
                        headerRow = rowNum; colITN = c;
                        headers = new ArrayList<>(curRow);
                        for (int i = 0; i < headers.size(); i++) {
                            String h = headers.get(i).trim().toUpperCase();
                            if ((h.equals("X")||h.equals("LAT")||h.equals("LATITUDE")) && colLat<0) colLat=i;
                            if ((h.equals("Y")||h.equals("LON")||h.equals("LNG")||h.equals("LONGITUDE")) && colLon<0) colLon=i;
                        }
                        latCnt = new int[Math.max(100, headers.size()+10)];
                        lonCnt = new int[Math.max(100, headers.size()+10)];
                        return;
                    }
                }
                return;
            }

            if ((colLat<0||colLon<0) && scanRows<10) {
                for (int c=0; c<curRow.size() && c<latCnt.length; c++) {
                    try {
                        double v = Double.parseDouble(curRow.get(c).replace(",","."));
                        if (v>=41.5&&v<=44.5&&v!=Math.floor(v)) latCnt[c]++;
                        if (v>=22.0&&v<=28.5&&v!=Math.floor(v)) lonCnt[c]++;
                    } catch (Exception ignored) {}
                }
                if (++scanRows==10) {
                    if (colLat<0) { int mx=0; for(int c=0;c<latCnt.length;c++) if(latCnt[c]>mx){mx=latCnt[c];colLat=c;} }
                    if (colLon<0) { int mx=0; for(int c=0;c<lonCnt.length;c++) if(lonCnt[c]>mx&&c!=colLat){mx=lonCnt[c];colLon=c;} }
                }
            }

            if (colITN<0||colLat<0||colLon<0) return;

            String itn = colITN<curRow.size() ? curRow.get(colITN).trim().replaceAll("[^0-9]","") : "";
            if (itn.isEmpty()||itn.equals("0")) return;
            try {
                double lat = Double.parseDouble(colLat<curRow.size()?curRow.get(colLat).replace(",","."):"0");
                double lon = Double.parseDouble(colLon<curRow.size()?curRow.get(colLon).replace(",","."):"0");
                if (lat==0||lon==0) return;
                Map<String,String> extra = new HashMap<>();
                List<String> skip = java.util.Arrays.asList("X.1","Y.1","MAPS","MAPS.1","РАЗЛИКА В МЕТРИ");
                for (int c=0;c<headers.size()&&c<curRow.size();c++) {
                    if (c==colITN||c==colLat||c==colLon) continue;
                    String h=headers.get(c).trim();
                    if (h.isEmpty()||skip.contains(h.toUpperCase())) continue;
                    String v=curRow.get(c).trim();
                    if (!v.isEmpty()&&!v.equals("0")&&!v.equals(".")) extra.put(h,v);
                }
                data.put(itn, new GpsRecord(itn,lat,lon,extra));
                if (data.size()%5000==0) {
                    final int n=data.size();
                    runOnUiThread(()->loadingText.setText("Заредени: "+n+" записа..."));
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void endDocument() {
            closeSsDb(); // затвори и изтрий temp DB
            final Map<String,GpsRecord> result = data;
            final String fname = filename;
            runOnUiThread(() -> {
                if (colLat<0||colLon<0) {
                    hideLoading();
                    Toast.makeText(MainActivity.this,"Не намирам координатни колони!",Toast.LENGTH_LONG).show();
                    return;
                }
                gpsData = result;
                hideLoading();
                statusText.setText(fname+": "+String.format("%,d",result.size())+" записа");
                Toast.makeText(MainActivity.this,"Заредени "+result.size()+" клиента!",Toast.LENGTH_SHORT).show();
            });
        }
    }



    // Търсене
    private void search() {
        String raw = itnInput.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "Въведи ИТН номер!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (gpsData.isEmpty()) {
            Toast.makeText(this, "Първо зареди Excel файла!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Парсирай ИТН номерата
        String[] parts = raw.split("[\\s,;\\n]+");
        foundRecords.clear();
        List<String> notFound = new ArrayList<>();

        for (String part : parts) {
            String itn = part.trim().replaceAll("[^0-9]", "");
            if (itn.isEmpty()) continue;
            GpsRecord rec = gpsData.get(itn);
            if (rec != null) foundRecords.add(rec);
            else notFound.add(itn);
        }

        // Изчисти стари маркери
        clearMarkers();

        // Добави нови маркери
        int[] colors = {
            Color.parseColor("#e94560"), Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"), Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"), Color.parseColor("#00BCD4")
        };

        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < foundRecords.size(); i++) {
            GpsRecord rec = foundRecords.get(i);
            GeoPoint point = new GeoPoint(rec.lat, rec.lon);
            points.add(point);

            Marker marker = new Marker(map);
            marker.setPosition(point);
            marker.setTitle("ИТН: " + rec.itn);
            marker.setSnippet(rec.getClient() + "\n" + rec.getPlace());
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            map.getOverlays().add(marker);
            markers.add(marker);
        }

        // Маршрутна линия
        if (points.size() > 1) {
            routeLine = new Polyline();
            routeLine.setPoints(points);
            routeLine.getOutlinePaint().setColor(Color.parseColor("#e94560"));
            routeLine.getOutlinePaint().setStrokeWidth(5f);
            map.getOverlays().add(routeLine);

            // Центрирай картата
            org.osmdroid.util.BoundingBox bb = org.osmdroid.util.BoundingBox.fromGeoPoints(points);
            map.zoomToBoundingBox(bb, true, 80);
        } else if (points.size() == 1) {
            map.getController().animateTo(points.get(0));
            map.getController().setZoom(16.0);
        }

        map.invalidate();

        // Покажи резултати
        if (!foundRecords.isEmpty()) {
            ResultAdapter adapter = new ResultAdapter(foundRecords);
            resultsList.setAdapter(adapter);
            resultsPanel.setVisibility(View.VISIBLE);
        }

        String msg = "Намерени: " + foundRecords.size();
        if (!notFound.isEmpty()) msg += " | Ненамерени: " + notFound.size();
        statusText.setText(msg);
    }

    private void clearMarkers() {
        for (Marker m : markers) map.getOverlays().remove(m);
        markers.clear();
        if (routeLine != null) { map.getOverlays().remove(routeLine); routeLine = null; }
        map.invalidate();
    }

    // Google Maps навигация
    private void openGoogleMaps() {
        if (foundRecords.isEmpty()) return;
        String url;
        if (foundRecords.size() == 1) {
            GpsRecord r = foundRecords.get(0);
            url = "https://maps.google.com/?q=" + r.lat + "," + r.lon;
        } else {
            GpsRecord o = foundRecords.get(0);
            GpsRecord d = foundRecords.get(foundRecords.size() - 1);
            StringBuilder wpts = new StringBuilder();
            for (int i = 1; i < foundRecords.size() - 1; i++) {
                if (wpts.length() > 0) wpts.append("|");
                wpts.append(foundRecords.get(i).lat).append(",").append(foundRecords.get(i).lon);
            }
            url = "https://www.google.com/maps/dir/?api=1&origin=" + o.lat + "," + o.lon
                + "&destination=" + d.lat + "," + d.lon;
            if (wpts.length() > 0) url += "&waypoints=" + wpts;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    // File picker за Excel
    private void pickExcelFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Избери Excel файл"), FILE_REQUEST);
    }

    // Гласово въвеждане
    private void startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQUEST);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bg-BG");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Кажи ИТН номера...");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "Гласовото въвеждане не е налично", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SPEECH_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spoken = results.get(0);
                String digits = spoken.replaceAll("[^0-9 ]", "").trim();
                itnInput.setText(digits.isEmpty() ? spoken : digits);
                search();
            }
        }

        if (requestCode == FILE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                showLoading("Зареждане на Excel...");
                new Thread(() -> {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        String name = uri.getLastPathSegment();
                        if (name == null) name = "excel.xlsx";
                        loadFromStream(is, name);
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            hideLoading();
                            Toast.makeText(this, "Грешка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }).start();
            }
        }
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (!perms.isEmpty())
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQUEST);
    }

    private void showLoading(String msg) {
        loadingText.setText(msg);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    // Модел на данните
    static class GpsRecord {
        String itn;
        double lat, lon;
        Map<String, String> extra;

        GpsRecord(String itn, double lat, double lon, Map<String, String> extra) {
            this.itn = itn; this.lat = lat; this.lon = lon; this.extra = extra;
        }

        String getClient() {
            if (extra.containsKey("Клиент ime")) return extra.get("Клиент ime");
            if (extra.containsKey("Клиент")) return extra.get("Клиент");
            return "";
        }

        String getPlace() {
            if (extra.containsKey("Нас място")) return extra.get("Нас място");
            if (extra.containsKey("Нас. място")) return extra.get("Нас. място");
            return "";
        }
    }

    // RecyclerView Adapter
    class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        List<GpsRecord> items;
        boolean[] expanded;

        ResultAdapter(List<GpsRecord> items) {
            this.items = items;
            this.expanded = new boolean[items.size()];
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, @SuppressLint("RecyclerView") int pos) {
            GpsRecord rec = items.get(pos);
            h.itnText.setText((pos + 1) + ". ИТН " + rec.itn);

            String client = rec.getClient();
            String place = rec.getPlace();
            h.clientText.setText(client.isEmpty() ? rec.itn : client);
            h.placeText.setText(place);
            h.placeText.setVisibility(place.isEmpty() ? View.GONE : View.VISIBLE);

            // Детайли бутон
            h.btnDetails.setOnClickListener(v -> {
                expanded[pos] = !expanded[pos];
                h.detailsLayout.setVisibility(expanded[pos] ? View.VISIBLE : View.GONE);
                h.btnDetails.setText(expanded[pos] ? "Скрий" : "Детайли");

                if (expanded[pos] && h.detailsLayout.getChildCount() == 0) {
                    // Попълни детайлите
                    h.detailsLayout.removeAllViews();
                    // ИТН
                    addDetailRow(h.detailsLayout, "ИТН", rec.itn);
                    addDetailRow(h.detailsLayout, "Коорд.", String.format("%.6f, %.6f", rec.lat, rec.lon));
                    for (Map.Entry<String, String> e : rec.extra.entrySet()) {
                        addDetailRow(h.detailsLayout, e.getKey(), e.getValue());
                    }
                }
            });

            // Кликни на ред → центрирай картата
            h.itemView.setOnClickListener(v -> {
                map.getController().animateTo(new GeoPoint(rec.lat, rec.lon));
                map.getController().setZoom(16.0);
            });
        }

        void addDetailRow(LinearLayout layout, String label, String value) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 2, 0, 2);

            TextView lbl = new TextView(MainActivity.this);
            lbl.setText(label);
            lbl.setTextColor(Color.parseColor("#e94560"));
            lbl.setTextSize(10f);
            lbl.setMinWidth(200);

            TextView val = new TextView(MainActivity.this);
            val.setText(value);
            val.setTextColor(Color.parseColor("#dddddd"));
            val.setTextSize(10f);

            row.addView(lbl);
            row.addView(val);
            layout.addView(row);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView itnText, clientText, placeText, btnDetails;
            LinearLayout detailsLayout;
            VH(View v) {
                super(v);
                itnText = v.findViewById(R.id.itn_text);
                clientText = v.findViewById(R.id.client_text);
                placeText = v.findViewById(R.id.place_text);
                btnDetails = v.findViewById(R.id.btn_details);
                detailsLayout = v.findViewById(R.id.details_layout);
            }
        }
    }

    @Override
    public void onResume() { super.onResume(); map.onResume(); }

    @Override
    public void onPause() { super.onPause(); map.onPause(); }
}
