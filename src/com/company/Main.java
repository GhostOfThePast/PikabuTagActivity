package com.company;

import org.jfree.chart.*;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.DateTickMarkPosition;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.time.Day;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.ApplicationFrame;
import org.jfree.ui.RefineryUtilities;
import org.jfree.util.ShapeUtilities;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Pattern;

public class Main {

    /**
     * Я пытался залогиниться программно и использовать эти куки, но без особых успехов.
     * Нужно просто залогиниться через браузер, из браузерной консоли разработчика достать куки "PHPSESS", "phpDug2"
     * командой document.cookies, потом записать эти две строки в ./res/cookies.txt
     * Возможно, браузер при дальнейших запросах стоит держать открытым.
     * Разумеется, клубничка и жесть должны быть включены в настройках, чтобы необходимые посты были подгружены.
     * Также рекомендуется увеличить количество постов на страницу, чтобы уменьшить количество запросов.
     */
    private static String PHPSESS = "getPHPSESS";
    private static String PHPDUG2 = "getphpDug2";

    /**
     * Подгружает куки из ./res/cookies. Первая строка - PHPSESS, вторая - phpDug2
     */
    private static void readCookies() {
        try {
            BufferedReader in = new BufferedReader(new FileReader("./res/cookies.txt"));
            PHPSESS = in.readLine();
            PHPDUG2 = in.readLine();
        } catch (IOException e) {
            System.out.println("Error while reading cookies");
            System.out.println(e.getMessage());
        }
    }

    /**
     * Будем слать запросы в 8 потоков.
     */
    private static final int THREADS = 8;
    private static ExecutorService executorService = Executors.newFixedThreadPool(THREADS);

    private static final String DATE_FORMAT = "dd-MM-yyyy";


    /**
     * День 0 - 01.01.2008, но первый пост с тегом my little pony появился на 1896 день 11.03.2013
     * Посчитаем посты, например, до 2864 дня 4.11.2015
     */
    public static final int NOW = 2864;
    public static final int FIRST = 1896;

    public static Document getHTML(String url, boolean cookies) throws IOException {
        Connection connection = Jsoup.connect(url);
        if (cookies) {
            connection = connection.cookie("PHPSESS", PHPSESS).cookie("phpDug2", PHPDUG2);
        }
        return connection.get();
    }

    /**
     * Возвращает нумерацию дней на Пикабу
     * @param s строка в формате дд-мм-гггг
     * @return номер дня
     * @throws ParseException
     */
    public static int getPikabuNumeration(String s) throws ParseException {
        return getPikabuNumeration(new SimpleDateFormat(DATE_FORMAT).parse(s));
    }

    /**
     * Возвращает нумерацию дней на Пикабу
     * @param date дата
     * @return номер дня
     * @throws ParseException
     */
    public static int getPikabuNumeration(Date date) throws ParseException {
        Date zeroDay = new SimpleDateFormat(DATE_FORMAT).parse("01-01-2008");
        long diff = date.getTime() - zeroDay.getTime();
        return (int) TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);
    }

    /**
     * Объединяет теги в одну строку, которая будет участвовать в поиске
     * @param tags теги
     * @return строка для поиска
     */
    public static String joinTags(String[] tags) {
        Arrays.sort(tags);
        StringBuilder stringBuilder = new StringBuilder();
        for (String tag: tags) stringBuilder.append(tag).append(",");
        return stringBuilder.substring(0, stringBuilder.length() - 1);
    }


    /**
     * Создает директорию при необходимости
     * @param directory
     */
    public static void createIfNotExists(File directory) {
        if (!directory.exists()) {
            System.out.println("Creating directory: " + directory.getPath());
            boolean result = false;
            try {
                directory.mkdirs();
                result = true;
            } catch(SecurityException ignored){
            }
            if(result) {
                System.out.println("Directory created");
            }
        }
    }

    /**
     * Возвращает представление тегов для записи на диск
     * @param tags теги
     * @return
     */
    public static String getPath(String[] tags) {
        return joinTags(tags).replaceAll(",", "__").replaceAll(" ", "+");
    }

    /**
     * Возвращает представление тегов для запросов к Пикабу
     * @param tags теги
     * @return
     * @throws UnsupportedEncodingException
     */
    public static String getURL(String[] tags) throws UnsupportedEncodingException {
        return URLEncoder.encode(joinTags(tags), "UTF-8");
    }

    /**
     * Подгружает ссылки на посты соответствующих тегов
     * @param tags теги, по которым ведется поиск
     * @param dayFrom номер дня, с которого нужно искать посты
     * @param dayTo номер дня, по который нужно искать посты
     * @throws FileNotFoundException
     * @throws UnsupportedEncodingException
     * @throws InterruptedException
     */
    public static void loadLinks(String[] tags, int dayFrom, int dayTo) throws FileNotFoundException, UnsupportedEncodingException, InterruptedException {
        // Потенциальные ссылки на посты
        final Pattern story = Pattern.compile("(.*)pikabu\\.ru/story(.*)");
        // Ссылки на комментарии, не нужны на этой фазе
        final Pattern comments = Pattern.compile("(.*)pikabu\\.ru/story(.*)#comment(.*)");
        // Ссылки на картинку с Печенькой внизу страницы, не нужны вообще
        final Pattern mascot = Pattern.compile("(.*)pikabu\\.ru/story/_(.*)");
        final ConcurrentSkipListSet<String> links = new ConcurrentSkipListSet<String>();
        List<Callable<Object>> todo = new ArrayList<Callable<Object>>();
        final String encodedTags = getURL(tags);
        for (int day = dayTo; day >= dayFrom; day--) {
            final int fDay = day;
            todo.add(new Callable<Object>() {
                @Override
                public Object call() {
                    int page = 1;
                    int loaded = 0;
                    while (true) {
                        boolean changed = false;
                        while (true) {
                            try {
                                Document doc = getHTML("http://pikabu.ru/search.php?t=" + encodedTags + "&d=" + fDay + "&page=" + page, true);
                                Elements elements = doc.select("body a");
                                for (org.jsoup.nodes.Element element: elements) {
                                    String href = element.attr("href");
                                    if (!href.startsWith("http://")) href = "http://" + href;
                                    if (story.matcher(href).matches() && !comments.matcher(href).matches() && !mascot.matcher(href).matches()) {
                                        int j = href.length() - 1;
                                        while (!Character.isDigit(href.charAt(j))) j--;
                                        href = href.substring(0, j + 1);
                                        links.add(href);
                                        loaded++;
                                        changed = true;
                                    }
                                }
                                break;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        System.out.println("Executed day = " + fDay + ", page = " + page + ", loaded " + loaded + " posts, overall links = " + links.size());
                        if (!changed) break;
                        page++;
                    }
                    return null;
                }});
        }
        executorService.invokeAll(todo);
        createIfNotExists(new File("./res/links/"));
        PrintWriter out = new PrintWriter("./res/links/" + getPath(tags) + ".txt", "UTF-8");
        for (String link : links) {
            out.println(link);
        }
        out.close();
    }

    /**
     * Сохраняет html страницы со ссылками на них в соответствующую директорию
     * @param tags теги, по которым нужно подгружать страницы. Ссылки на посты этого набора тегов уже должны быть подгружены
     * @throws IOException
     * @throws InterruptedException
     */
    public static void loadHTML(String[] tags) throws IOException, InterruptedException {
        BufferedReader in = new BufferedReader( new InputStreamReader(
                new FileInputStream("./res/links/" + getPath(tags) + ".txt"), StandardCharsets.UTF_8));
        createIfNotExists(new File("./res/htmlDumps/" + getPath(tags) + "/"));
        String s;
        final List<String> links = new ArrayList<String>();
        while ((s = in.readLine()) != null) if (!s.isEmpty()) {
            links.add(s);
        }
        List<Callable<Object>> todo = new ArrayList<Callable<Object>>();
        for (int i = 0; i < links.size(); i++) {
            final String link = links.get(i);
            final int finalI = i;
            todo.add(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    System.out.println("Processing post " + (finalI + 1) + "/" + links.size());
                    System.out.println("Loading " + link + "...");
                    while (true) {
                        try {
                            Document doc = getHTML(link, false);
                            // Оставим в качестве имени только часть после pikabu.ru/story/, благо, она уникальна и содержит номер поста
                            PrintWriter out = new PrintWriter("./res/htmlDumps/" + getPath(tags) + "/" + link.split("pikabu\\.ru/story/")[1].replaceAll("\\W+", ""), "UTF-8");
                            out.println(link);
                            out.println(doc.toString());
                            out.close();
                            break;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    return null;
                }
            });
        }
        executorService.invokeAll(todo);
    }

    public static class Post {

        public int pluses, minuses, rating, comments;
        public long unixTime;
        public Date date;
        public String author, link;
        public List<String> tags = new ArrayList<String>();
        public boolean hard, erotic;

        /**
         * Создает пост из HTML-страницы
         * @param doc HTML
         * @param link ссылка на пост
         */
        public Post(Document doc, String link) {
            Elements rating = doc.select("div.b-story__rating");
            Elements storyInfo = doc.select("div.b-story-info__main");
            Elements detailDate = doc.select("a.detailDate");
            Elements storyHeader = doc.select("div.b-story__header-additional");
            Elements tags = storyHeader.select("span.tag");
            this.pluses = Integer.parseInt(rating.get(0).attr("data-pluses"));
            this.minuses = Integer.parseInt(rating.get(0).attr("data-minuses"));
            this.rating = pluses - minuses;
            this.comments = 0;
            try {
                this.comments = Integer.parseInt(storyInfo.get(0).text().split(" ")[0]);
            } catch (Exception ignored) {
                // Нет комментариев
            }
            this.link = link;
            this.author = storyHeader.get(0).child(1).child(2).text();
            this.date = new Date();
            this.date.setTime(Long.parseLong(detailDate.get(0).attr("title")) * 1000);
            this.unixTime = Long.parseLong(detailDate.get(0).attr("title"));
            for (int j = 0; j < tags.size(); j++) {
                String tag = tags.get(j).text().toLowerCase();
                if (tag.equals("жесть")) {
                    hard = true;
                }
                this.tags.add(tag);
            }
            Element icon = doc.select("a.story_straw").get(0);
            if (!icon.attr("style").split(";")[0].toLowerCase().equals("display: none")) {
                erotic = true;
            }
        }

        public Post(JSONObject obj) throws JSONException, ParseException {
            this.rating = obj.getInt("rating");
            this.minuses = obj.getInt("minuses");
            this.pluses = obj.getInt("pluses");
            this.comments = obj.getInt("comments");
            this.author = obj.getString("author");
            this.link = obj.getString("link");
            this.unixTime = obj.getLong("unixTIme");
            this.date = new SimpleDateFormat(DATE_FORMAT).parse(obj.getString("date"));
            this.hard = obj.getBoolean("hard");
            this.erotic = obj.getBoolean("erotic");
            JSONArray tags = obj.getJSONArray("tags");
            for (int i = 0; i < tags.length(); i++) {
                this.tags.add(tags.getString(i));
            }
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("rating", rating);
            result.put("minuses", minuses);
            result.put("pluses", pluses);
            result.put("comments", comments);
            result.put("author", author);
            result.put("link", link);
            result.put("unixTIme", unixTime);
            result.put("date", new SimpleDateFormat(DATE_FORMAT).format(date));
            result.put("hard", hard);
            result.put("erotic", erotic);
            JSONArray tags = new JSONArray();
            for (int i = 0; i < this.tags.size(); i++) {
                tags.put(this.tags.get(i));
            }
            result.put("tags", tags);
            return result;
        }
    }

    /**
     * Загружает пост из файла
     * @param file файл со ссылкой и HTML
     * @return пост
     * @throws IOException
     */
    public static Post loadPost(File file) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        String link = in.readLine();
        StringBuilder stringBuilder = new StringBuilder();
        String s;
        while ((s = in.readLine()) != null) {
            stringBuilder.append(s).append("\n");
        }
        return new Post(Jsoup.parse(stringBuilder.toString()), link);
    }

    /**
     * Создает массив постов из уже загруженных файлов с HTML
     * @param tags список тегов
     * @return массив постов
     */
    public static Post[] loadPosts(String[] tags) throws IOException, InterruptedException {
        File dumpDir = new File("./res/htmlDumps/" + getPath(tags) + "/");
        final File[] files = dumpDir.listFiles();
        final Post[] result = new Post[files.length];
        List<Callable<Object>> todo = new ArrayList<Callable<Object>>();
        for (int i = 0; i < files.length; i++) {
            final int finalI = i;
            final File file = files[i];
            todo.add(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    result[finalI] = loadPost(file);
                    System.out.println("Created post " + (finalI + 1) + "/" + files.length + ": " + result[finalI].link);
                    return null;
                }
            });
        }
        executorService.invokeAll(todo);
        return result;
    }

    /**
     * Преобразует загруженные HTML-страницы в JSON формат
     * @param tags список тегов
     * @throws IOException
     */
    public static void convertToJSON(String[] tags) throws IOException, JSONException, InterruptedException {
        Post[] posts = loadPosts(tags);
        JSONArray jsonArray = new JSONArray();
        for (Post p : posts) {
            List<String> postTags = p.tags;
            /*
                Что за бредовые проверки, ведь этот пост вернулся к нам по запросу?
                К сожалению, поиск иногда выдает ложные результаты, все вопросы к дяде админу.
             */
            for (int i = 0; i < postTags.size(); i++) {
                postTags.set(i, postTags.get(i).toLowerCase());
            }
            boolean relevant = true;
            for (String tag: tags) {
                if (!postTags.contains(tag.toLowerCase())) {
                    relevant = false;
                }
            }
            if (relevant) {
                jsonArray.put(p.toJSON());
            }
        }
        createIfNotExists(new File("./res/json/"));
        PrintWriter out = new PrintWriter("./res/json/" + getPath(tags) + ".json", "UTF-8");
        out.println(jsonArray.toString());
        out.close();
    }

    /**
     * Загружает посты из JSON файла
     * @param tags список тегов
     * @return массив постов
     * @throws IOException
     * @throws JSONException
     * @throws ParseException
     */
    public static Post[] loadFromJSON(String[] tags) throws IOException, JSONException, ParseException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("./res/json/" + getPath(tags) + ".json"), StandardCharsets.UTF_8));
        StringBuilder stringBuilder = new StringBuilder();
        String s;
        while ((s = in.readLine()) != null) {
            stringBuilder.append(s).append("\n");
        }
        JSONArray jsonArray = new JSONArray(stringBuilder.toString());
        Post[] result = new Post[jsonArray.length()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Post(jsonArray.getJSONObject(i));
        }
        return result;
    }

    /**
     * Сделать нужные шаги преобразования данных
     * @param tags список тегов
     * @param loadLinks загрузить список ссылок
     * @param from номер дня, с которого грузить
     * @param to номер дня, по который грузить
     * @param loadHTML загрузить HTML страницы
     * @param convertToJSON преобразовать в JSON
     * @throws IOException
     * @throws InterruptedException
     * @throws JSONException
     */
    public static void loadInfo(String[] tags, boolean loadLinks, int from, int to,
                                boolean loadHTML, boolean convertToJSON) throws IOException, InterruptedException, JSONException {
        if (loadLinks) {
            readCookies();
            loadLinks(tags, from, to);
        }
        if (loadHTML) {
            loadHTML(tags);
        }
        if (convertToJSON) {
            convertToJSON(tags);
        }
    }

    /**
     * Отображает точечную диаграмму какой-либо величины поста по датам
     * и какой-либо агрегирующей функции постов (сумма рейтингов,
     * среднее количество комментариев, количество постов и т. д.) за день,
     * возвращает объект, позволяющий сохранить диаграмму.
     * @param tags теги постов
     * @param posts сами посты
     * @param targetName название отображаемой величины
     * @param target функция вычисления величины по посту
     * @param aggregateName название агрегируемой величины
     * @param aggregate агрегирующая функция
     * @param fromDate левая граница отображения по датам
     * @param toDate правая граница отображения по датам
     * @param minTarget нижняя граница отображения величины
     * @param maxTarget верхняя граница отображения величины
     * @return
     * @throws ParseException
     * @throws UnsupportedEncodingException
     */
    public static JFreeChart plot(String tags[], Post[] posts, String targetName, Function<Post, Double> target,
                                  String aggregateName, Function<List<Post>, Double> aggregate,
                                  String fromDate, String toDate, double minTarget, double maxTarget) throws ParseException, UnsupportedEncodingException {
        final XYSeriesCollection collection = new XYSeriesCollection();
        final JFreeChart chart = ChartFactory.createScatterPlot(joinTags(tags) + ":" + targetName + " over time", "Date", targetName, collection);
        XYPlot xyPlot = (XYPlot)chart.getPlot();
        DateAxis xAxis = new DateAxis("Date");
        xAxis.setTickMarkPosition(DateTickMarkPosition.MIDDLE);
        xyPlot.setDomainAxis(xAxis);
        if (minTarget < maxTarget) {
            xyPlot.getRangeAxis().setRange(minTarget, maxTarget);
        }
        if (fromDate != null && toDate != null) {
            xyPlot.getDomainAxis().setRange(new SimpleDateFormat(DATE_FORMAT).parse(fromDate).getTime(),
                    new SimpleDateFormat(DATE_FORMAT).parse(toDate).getTime());
        }
        final ChartPanel chartPanel = new ChartPanel(chart);
        JFrame frame = new ApplicationFrame(targetName);
        chartPanel.setPreferredSize(new java.awt.Dimension(1920, 1080));
        frame.setContentPane(chartPanel);
        frame.pack();
        RefineryUtilities.centerFrameOnScreen(frame);
        XYSeries value = new XYSeries("Post " + targetName.toLowerCase());
        XYSeries aggregated = new XYSeries("Day " + targetName.toLowerCase() + " " + aggregateName);
        Map<Day, List<Post>> dayRatings = new HashMap<Day, List<Post>>();
        for (int i = 0; i < posts.length; i++) {
            Day day = new Day(posts[i].date);
            value.add(day.getMiddleMillisecond(), target.apply(posts[i]));
            if (dayRatings.get(day) == null) {
                dayRatings.put(day, new ArrayList<Post>());
            }
            dayRatings.get(day).add(posts[i]);
        }
        for (Map.Entry<Day, List<Post>> entry : dayRatings.entrySet()) {
            aggregated.add(entry.getKey().getMiddleMillisecond(), aggregate.apply(entry.getValue()));
        }
        collection.removeAllSeries();
        collection.addSeries(aggregated);
        collection.addSeries(value);
        XYItemRenderer renderer = xyPlot.getRenderer();
        xyPlot.setBackgroundPaint(Color.BLACK);
        Shape diamond = ShapeUtilities.createDiamond(3f);
        renderer.setSeriesShape(0, diamond);
        renderer.setSeriesPaint(0, Color.GREEN);
        Color darkGreen = new Color(0, 127, 0);
        Shape cross = ShapeUtilities.createDiagonalCross(0.25f, 0.25f);
        renderer.setSeriesShape(1, cross);
        renderer.setSeriesPaint(1, darkGreen);
        chart.setBackgroundPaint(Color.BLACK);
        chart.setBorderPaint(darkGreen);
        chart.getLegend().setBackgroundPaint(Color.BLACK);
        chart.getLegend().setItemPaint(darkGreen);
        chart.getTitle().setPaint(Color.GREEN);
        xyPlot.setDomainCrosshairPaint(darkGreen);
        xyPlot.setDomainGridlinePaint(darkGreen);
        xyPlot.setDomainMinorGridlinePaint(darkGreen);
        xyPlot.setRangeGridlinePaint(darkGreen);
        xyPlot.setRangeCrosshairPaint(darkGreen);
        xyPlot.setRangeMinorGridlinePaint(darkGreen);
        xyPlot.getDomainAxis().setAxisLinePaint(darkGreen);
        xyPlot.getDomainAxis().setLabelPaint(Color.GREEN);
        xyPlot.getDomainAxis().setTickLabelPaint(darkGreen);
        xyPlot.getRangeAxis().setAxisLinePaint(darkGreen);
        xyPlot.getRangeAxis().setLabelPaint(Color.GREEN);
        xyPlot.getRangeAxis().setTickLabelPaint(darkGreen);
        chartPanel.addChartMouseListener(new ChartMouseListener() {
            private ChartMouseEvent currentEvent = null;

            @Override
            public void chartMouseClicked(ChartMouseEvent chartMouseEvent) {
                if (currentEvent != null) chartMouseEvent = currentEvent;
                chartMouseEvent.getTrigger().getLocationOnScreen();
                System.out.println("Click event!");
                XYPlot xyPlot2 = chartPanel.getChart().getXYPlot();
                // Problem: the coordinates displayed are the one of the previously selected point !
                System.out.println(xyPlot2.getDomainCrosshairValue() + " "
                        + xyPlot2.getRangeCrosshairValue());
                long unixTime = (long) (xyPlot2.getDomainCrosshairValue());
                Date date = new Date(unixTime);
                System.out.println(date);
            }

            @Override
            public void chartMouseMoved(ChartMouseEvent chartMouseEvent) {
                currentEvent = chartMouseEvent;
            }
        });
        frame.setVisible(true);
        return chart;
    }

    /**
     * Сохраняет диаграмму
     * @param chart диаграмма
     * @param tags теги постов
     * @param name название диаграммы
     * @param width ширина в пикселях
     * @param height высота в пикселях
     * @throws IOException
     */
    public static void savePlot(JFreeChart chart, String[] tags, String name, int width, int height) throws IOException {
        createIfNotExists(new File("./res/plot/" + getPath(tags) + "/"));
        ChartUtilities.saveChartAsPNG(new File("./res/plot/" + getPath(tags) + "/" + name + ".png"), chart, width, height);
    }

    /**
     * Примитивная аналитика: распределение постов по условным категориям рейтинга, лучшие/худшие посты
     * @param posts посты для анализа
     * @param bestCount количество лучших постов
     * @param worstCount количество худших постов
     */
    public static void simpleAnalytics(Post[] posts, int bestCount, int worstCount) {
        int best = 0, hot = 0, soSo = 0, trash = 0;
        for (int i = 0; i < posts.length; i++) {
            if (posts[i].rating >= 100) {
                best++;
            } else if (posts[i].rating >= 30) {
                hot++;
            } else if (posts[i].rating > -25) {
                soSo++;
            } else {
                trash++;
            }
        }
        System.out.println("Best: " + best);
        System.out.println("Hot: " + hot);
        System.out.println("So-so: " + soSo);
        System.out.println("Trash: " + trash);

        Arrays.sort(posts, new Comparator<Post>() {
            @Override
            public int compare(Post o1, Post o2) {
                return Integer.compare(o1.rating, o2.rating);
            }
        });
        System.out.println("\n\n\n\nThe Bottom:");
        for (int i = 0; i < worstCount; i++) {
            System.out.println(posts[i].author);
            System.out.println(posts[i].link);
            System.out.println(posts[i].rating);
        }
        Arrays.sort(posts, new Comparator<Post>() {
            @Override
            public int compare(Post o1, Post o2) {
                return Integer.compare(o2.rating, o1.rating);
            }
        });
        System.out.println("\n\n\n\nThe Top:");
        for (int i = 0; i < bestCount; i++) {
            System.out.println(posts[i].author);
            System.out.println(posts[i].link);
            System.out.println(posts[i].rating);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException, JSONException, ParseException {
        String[] tags = new String[]{"my little pony"};
        int from = getPikabuNumeration("11-03-2013");
        int to = getPikabuNumeration("04-11-2015");
        loadInfo(tags, false, from, to, false, false);

        Post[] posts = loadFromJSON(tags);
        // Отличные ребята, но несколько портят статистику в плюс и в минус
        //posts = Arrays.stream(posts).filter(p -> !p.author.equals("Oblomoff")
        //        && !p.author.equals("Dalbek") && !p.author.equals("TechnoPhoenixPaladin")).toArray(Post[]::new);
        //simpleAnalytics(posts, 50, 50);


        // Введем вспомогательные функции

        Function<List<Post>, Double> sumRating = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.rating;
            return sum * 1.0;
        };
        Function<List<Post>, Double> meanRating = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.rating;
            return sum * 1.0 / posts1.size();
        };
        Function<List<Post>, Double> sumPluses = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.pluses;
            return sum * 1.0;
        };
        Function<List<Post>, Double> meanPluses = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.pluses;
            return sum * 1.0 / posts1.size();
        };
        Function<List<Post>, Double> sumMinuses = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.minuses;
            return sum * 1.0;
        };
        Function<List<Post>, Double> meanMinuses = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.minuses;
            return sum * 1.0 / posts1.size();
        };
        Function<List<Post>, Double> sumComments = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.comments;
            return sum * 1.0;
        };
        Function<List<Post>, Double> meanComments = posts1 -> {
            int sum = 0;
            for (Post p : posts1) sum += p.comments;
            return sum * 1.0 / posts1.size();
        };

        JFreeChart plot = plot(tags, posts, "minuses", p -> 1.0 * p.minuses, "sum",
                sumMinuses, "11-03-2013", "4-11-2015", 0, 0);
        Thread.sleep(1000);
        savePlot(plot, tags, "sum minuses full", 800, 600);

        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);
    }
}
