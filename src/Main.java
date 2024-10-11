import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.print.PrinterException;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.Timer;
import java.util.TimerTask;

public class Main {
    static final JFrame frame = new JFrame("Text Editor");
    static final JTextPane editorPane = new JTextPane();
    static final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    static final JLabel statusLabel = new JLabel("Статус: ");
    static File currentFile = null;
    static final UndoManager undoManager = new UndoManager();

    static String lastSearchText = ""; // Последний найденный текст
    static int lastSearchIndex = -1;   // Последняя позиция найденного текста
    private static SimpleAttributeSet copiedAttributes = new SimpleAttributeSet();
    static Properties settings = new Properties();

    private static final int PAGE_HEIGHT = 800; // Высота одной страницы в пикселях
    private static int currentPage = 0;

    public static void main(String[] args) {
        loadSettings();
        frame.setSize(screenSize.width, screenSize.height - 100);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        editorPane.setContentType("text/plain");
        editorPane.setEditable(true);
        editorPane.setFont(new Font(settings.getProperty("fontName", "Times New Roman"), Font.PLAIN, Integer.parseInt(settings.getProperty("fontSize", "14"))));

        // Настраиваем ScrollPane и Pane
        editorPane.setEditorKit(new javax.swing.text.StyledEditorKit()); // Обычный текст
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE); // Почтение к Display Properties

        editorPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane scrollPane = new JScrollPane(editorPane);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER); // Отключаем горизонтальную полосу прокрутки
        frame.add(scrollPane, BorderLayout.CENTER);

        addToolBar();

        boolean standardMenu = false;

        if (standardMenu) {
            addStandardMenu(); // Стандартное меню
        } else {
            addWordLikeMenu(); // Word-подобное меню
        }
        addFooterPanel();

        editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), "saveFile");
        editorPane.getActionMap().put("saveFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveFile(false);
            }
        });

        Timer autoSaveTimer = new Timer();
        autoSaveTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                saveFile(false);
            }
        }, 300000, 300000);

        editorPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                System.out.println("Insert update");
                updateStatus();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                System.out.println("Remove update");
                updateStatus();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                System.out.println("Changed update");
                updateStatus();
            }
        });

        addContextMenu();

        updateStatus();

        editorPane.addCaretListener(e -> updateStatus());

        frame.setVisible(true);
    }


    static void addStandardMenu() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("Файл");
        fileMenu.add(createMenuItem("Создать", e -> createNewFile()));
        fileMenu.add(createMenuItem("Открыть", e -> openFile()));
        fileMenu.add(createMenuItem("Сохранить", e -> saveFile(false)));
        fileMenu.add(createMenuItem("Сохранить как", e -> saveAsFile()));
        fileMenu.add(createMenuItem("Печать", e -> printFile()));
        fileMenu.add(createMenuItem("Закрыть", e -> closeFile()));

        JMenu editMenu = new JMenu("Изменить");
        editMenu.add(new DefaultEditorKit.CutAction()).setText("Вырезать");
        editMenu.add(new DefaultEditorKit.CopyAction()).setText("Копировать");
        editMenu.add(new DefaultEditorKit.PasteAction()).setText("Вставить");
        editMenu.add(createMenuItem("Удалить", e -> deleteNextChar()));
        editMenu.add(createMenuItem("Найти", e -> findText()));
        editMenu.add(createMenuItem("Найти далее", e -> findNext()));
        editMenu.add(createMenuItem("Найти ранее", e -> findPrevious()));
        editMenu.add(createMenuItem("Заменить", e -> replaceText()));
        editMenu.add(createMenuItem("Перейти", e -> goToLine()));
        editMenu.add(createMenuItem("Выбрать все", e -> selectAllText()));
        editMenu.add(createMenuItem("Дата и время", e -> insertDateTime()));

        JMenu setting = new JMenu("Настройки");
        setting.add(createMenuItem("Цвет текста", e -> changeTextColor()));
        setting.add(createMenuItem("Цвет фона", e -> changeBackgroundColor()));

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(setting);

        frame.setJMenuBar(menuBar);
    }

    private static JMenuItem createMenuItem(String text, ActionListener action) {
        JMenuItem menuItem = new JMenuItem(text);
        menuItem.addActionListener(action);
        return menuItem;
    }

    static JLabel pageCounterLabel = new JLabel("Страница: 1");

    static void addFooterPanel() {
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pageCounterLabel = new JLabel("Страница: 1");
        footerPanel.add(pageCounterLabel);
        footerPanel.add(statusLabel);
        frame.add(footerPanel, BorderLayout.SOUTH);
    }

    private static void updateStatus() {
        try {
            String text = editorPane.getDocument().getText(0, editorPane.getDocument().getLength());

            // Количество абзацев
            int paragraphs = text.split("\n").length;

            // Количество слов
            int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;

            // Количество символов
            int characters = text.length();

            // Количество символов без пробелов
            int charactersWithoutSpaces = text.replace(" ", "").length();

            // Количество предложений (поиск по ".", "!", "?")
            int sentences = text.split("[.!?]").length;

            // Количество специальных символов (все, кроме букв и цифр)
            int specialCharacters = text.replaceAll("[\\w\\s]", "").length();

            // Количество букв латинского алфавита
            int latinLetters = text.replaceAll("[^A-Za-z]", "").length();

            // Количество букв русского алфавита
            int russianLetters = text.replaceAll("[^А-Яа-яЁё]", "").length();

            // Количество цифр
            int digits = text.replaceAll("[^0-9]", "").length();

            // Количество знаков препинания (",", ".", "!", "?", ";", ":", и другие)
            int punctuationMarks = text.replaceAll("[^.,!?;:\\-()\"']", "").length();

            // Обновляем строку состояния
            statusLabel.setText(
                    String.format("Абзацы: %d | Предложения: %d | Слова: %d | Символы: %d | Символы без пробелов: %d | " +
                                    "Спец. символы: %d | Лат. буквы: %d | Рус. буквы: %d | Цифры: %d | Знаки препинания: %d",
                            paragraphs, sentences, words, characters, charactersWithoutSpaces, specialCharacters, latinLetters, russianLetters, digits, punctuationMarks)
            );

            // Обновляем текущую страницу
            updatePageStatus();

        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private static void updatePageStatus() {
        try {
            int caretPosition = editorPane.getCaretPosition();
            Rectangle caretRectangle = editorPane.modelToView(caretPosition);
            if (caretRectangle != null) {
                int yPosition = caretRectangle.y;
                currentPage = (yPosition / PAGE_HEIGHT) + 1;
                pageCounterLabel.setText("Страница: " + currentPage);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }


    private static void createNewFile() {
        editorPane.setText("");
        currentFile = null;
        frame.setTitle("TextEditor - Новый файл");
        updateStatus();
    }

    private static void openFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("Текстовые файлы (*.txt)", "txt")); // Добавляем фильтр файлов
        int option = fileChooser.showOpenDialog(frame);
        if (option == JFileChooser.APPROVE_OPTION) {
            currentFile = fileChooser.getSelectedFile();
            try (BufferedReader reader = new BufferedReader(new FileReader(currentFile))) {
                editorPane.read(reader, null);
                frame.setTitle("Text Editor - " + currentFile.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Ошибка при открытии файла.");
            }
            updateStatus();
        }
    }

    private static void saveFile(boolean saveAs) {
        if (saveAs || currentFile == null) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("Текстовые файлы (*.txt)", "txt")); // Добавляем фильтр файлов
            int option = fileChooser.showSaveDialog(frame);
            if (option == JFileChooser.APPROVE_OPTION) {
                currentFile = fileChooser.getSelectedFile();
            } else {
                return;
            }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile))) {
            editorPane.write(writer);
            frame.setTitle("Text Editor - " + currentFile.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Ошибка при сохранении файла.");
        }
    }

    private static void saveAsFile() {
        saveFile(true);
    }

    private static void printFile() {
        try {
            boolean complete = editorPane.print();
            if (complete) {
                JOptionPane.showMessageDialog(frame, "Печать завершена.");
            } else {
                JOptionPane.showMessageDialog(frame, "Печать была отменена.");
            }
        } catch (PrinterException ex) {
            JOptionPane.showMessageDialog(frame, "Ошибка при печати: " + ex.getMessage());
        }
    }

    private static void closeFile() {
        int confirm = JOptionPane.showConfirmDialog(frame, "Вы действительно хотите закрыть программу?", "Подтверждение", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            frame.dispose();
        }
    }

    private static void deleteNextChar() {
        int position = editorPane.getCaretPosition();
        try {
            if (position < editorPane.getDocument().getLength()) {
                editorPane.getDocument().remove(position, 1);
            } else {
                Toolkit.getDefaultToolkit().beep();
            }
        } catch (Exception e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private static void findText() {
        String searchText = JOptionPane.showInputDialog(frame, "\u0412\u0432\u0435\u0434\u0438\u0442\u0435 \u0442\u0435\u043a\u0441\u0442 \u0434\u043b\u044f \u043f\u043e\u0438\u0441\u043a\u0430:");
        if (searchText != null && !searchText.isEmpty()) {
            lastSearchText = searchText;
            lastSearchIndex = -1; // Сброс индекса перед началом нового поиска
            highlightSearchResults(searchText); // Выделяем все совпадения
            findNext(); // Перемещаемся к первому совпадению сразу
        }
    }

    private static void findNext() {
        if (lastSearchText == null || lastSearchText.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "\u0421\u043d\u0430\u0447\u0430ла используйте функцию '\u041d\u0430\u0439т\u0438'.");
            return;
        }

        try {
            // Удаляем текущее выделение, если оно есть
            removeCurrentHighlight(editorPane);

            String text = editorPane.getDocument().getText(0, editorPane.getDocument().getLength());
            lastSearchIndex = text.indexOf(lastSearchText, lastSearchIndex + 1);
            if (lastSearchIndex != -1) {
                // Удаляем выделение текущего совпадения из всех общих выделений
                removeSpecificHighlight(editorPane, lastSearchIndex, lastSearchIndex + lastSearchText.length());

                // Устанавливаем новое выделение красным цветом
                setCurrentHighlight(lastSearchIndex, lastSearchIndex + lastSearchText.length());

                // Устанавливаем каретку на найденное совпадение
                editorPane.setCaretPosition(lastSearchIndex);

                // Прокручиваем к видимой области с совпадением
                editorPane.scrollRectToVisible(editorPane.modelToView(lastSearchIndex));
            } else {
                JOptionPane.showMessageDialog(frame, "\u0422\u0435кст не найден.");
                lastSearchIndex = -1; // Сброс индекса, чтобы начать поиск заново при следующем вызове
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "\u041eшибка при поиске текста.");
        }
    }

    private static void removeSpecificHighlight(JTextComponent textComp, int start, int end) {
        Highlighter highlighter = textComp.getHighlighter();
        Highlighter.Highlight[] highlights = highlighter.getHighlights();

        for (Highlighter.Highlight highlight : highlights) {
            if (highlight.getPainter() == allMatchesHighlighter && highlight.getStartOffset() == start && highlight.getEndOffset() == end) {
                highlighter.removeHighlight(highlight);
                break;
            }
        }
    }

    private static void findPrevious() {
        if (lastSearchText == null || lastSearchText.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Сначала используйте функцию 'Найти'.");
            return;
        }

        try {
            // Удаляем текущее выделение, если оно есть
            removeCurrentHighlight(editorPane);

            String text = editorPane.getDocument().getText(0, editorPane.getDocument().getLength());
            lastSearchIndex = text.lastIndexOf(lastSearchText, lastSearchIndex - 1);
            if (lastSearchIndex != -1) {
                setCurrentHighlight(lastSearchIndex, lastSearchIndex + lastSearchText.length());

                // Устанавливаем каретку на найденное совпадение
                editorPane.setCaretPosition(lastSearchIndex);

                // Прокручиваем к видимой области с совпадением
                editorPane.scrollRectToVisible(editorPane.modelToView(lastSearchIndex));
            } else {
                JOptionPane.showMessageDialog(frame, "Текст не найден.");
                lastSearchIndex = text.length(); // Переместиться к концу, чтобы начать с конца при следующем поиске назад
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Ошибка при поиске текста.");
        }
    }

    private static void highlightSearchResults(String searchText) {
        removeHighlights(editorPane); // Удаляем старые выделения

        try {
            Highlighter highlighter = editorPane.getHighlighter();
            Document doc = editorPane.getDocument();
            String text = doc.getText(0, doc.getLength());
            int pos = 0;

            // Выделяем все совпадения цветом YELLOW
            while ((pos = text.indexOf(searchText, pos)) >= 0) {
                highlighter.addHighlight(pos, pos + searchText.length(), allMatchesHighlighter);
                pos += searchText.length();
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private static void setCurrentHighlight(int start, int end) {
        try {
            Highlighter highlighter = editorPane.getHighlighter();
            // Добавляем новое текущее выделение другим цветом (RED)
            currentHighlight = highlighter.addHighlight(start, end, currentMatchHighlighter);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private static void removeHighlights(JTextComponent textComp) {
        Highlighter highlighter = textComp.getHighlighter();
        Highlighter.Highlight[] highlights = highlighter.getHighlights();

        for (Highlighter.Highlight highlight : highlights) {
            if (highlight.getPainter() == allMatchesHighlighter) {
                highlighter.removeHighlight(highlight);
            }
        }
    }

    private static void removeCurrentHighlight(JTextComponent textComp) {
        if (currentHighlight != null) {
            Highlighter highlighter = textComp.getHighlighter();
            highlighter.removeHighlight(currentHighlight);
            currentHighlight = null;
        }
    }

    // Переменные для хранения текущего выделения
    private static Object currentHighlight = null;

    // Определяем Highlighter для всех совпадений и текущего совпадения
    private static final Highlighter.HighlightPainter allMatchesHighlighter = new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW);
    private static final Highlighter.HighlightPainter currentMatchHighlighter = new DefaultHighlighter.DefaultHighlightPainter(Color.RED);


    private static void replaceText() {
        JPanel panel = new JPanel(new GridLayout(2, 2));
        JTextField findField = new JTextField(10);
        JTextField replaceField = new JTextField(10);
        panel.add(new JLabel("Найти:"));
        panel.add(findField);
        panel.add(new JLabel("Заменить на:"));
        panel.add(replaceField);

        int option = JOptionPane.showConfirmDialog(frame, panel, "Заменить текст", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            String findText = findField.getText();
            String replaceText = replaceField.getText();
            try {
                String content = editorPane.getDocument().getText(0, editorPane.getDocument().getLength());

                // Проверяем все вхождения слова и заменяем его вручную
                StringBuilder newContent = new StringBuilder();
                int index = 0;
                int findLength = findText.length();

                while (index < content.length()) {
                    int foundIndex = content.indexOf(findText, index);
                    if (foundIndex == -1) {
                        newContent.append(content.substring(index));
                        break;
                    }
                    // Проверяем, является ли найденный текст отдельным словом
                    boolean isSeparateWord = (foundIndex == 0 || !Character.isLetterOrDigit(content.charAt(foundIndex - 1)))
                            && (foundIndex + findLength == content.length() || !Character.isLetterOrDigit(content.charAt(foundIndex + findLength)));
                    if (isSeparateWord) {
                        newContent.append(content, index, foundIndex);
                        newContent.append(replaceText);
                        index = foundIndex + findLength;
                    } else {
                        newContent.append(content.charAt(index));
                        index++;
                    }
                }

                // Обновляем текст в editorPane
                editorPane.setText(newContent.toString());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Ошибка при замене текста.");
            }
        }
    }

    private static void goToLine() {
        String lineStr = JOptionPane.showInputDialog(frame, "Введите номер строки:");
        try {
            int line = Integer.parseInt(lineStr);

            // Получаем корневой элемент документа
            Element root = editorPane.getDocument().getDefaultRootElement();

            // Проверяем, что номер строки находится в пределах допустимого диапазона
            if (line < 1 || line > root.getElementCount()) {
                throw new IndexOutOfBoundsException("Неверный номер строки");
            }

            // Получаем позицию начала указанной строки
            int position = root.getElement(line - 1).getStartOffset();

            // Устанавливаем каретку на эту позицию
            editorPane.setCaretPosition(position);

            // Прокручиваем к видимой области, чтобы выбранная строка была видна
            editorPane.scrollRectToVisible(editorPane.modelToView(position));

            // Устанавливаем фокус на редактор, чтобы обновить отображение
            editorPane.requestFocusInWindow();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(frame, "Введите корректное числовое значение.");
        } catch (IndexOutOfBoundsException e) {
            JOptionPane.showMessageDialog(frame, "Неверный номер строки.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Ошибка при переходе к строке.");
        }
    }

    private static void selectAllText() {
        editorPane.requestFocusInWindow(); // Устанавливаем фокус на editorPane
        editorPane.selectAll(); // Выделяем весь текст
    }

    private static void insertDateTime() {
        SimpleDateFormat formatter = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        String dateTime = formatter.format(new Date());

        int position = editorPane.getCaretPosition();
        try {
            // Убедитесь, что позиция корректна для вставки
            if (position < 0 || position > editorPane.getDocument().getLength()) {
                throw new BadLocationException("Неверная позиция вставки", position);
            }

            editorPane.getDocument().insertString(position, dateTime, null);
        } catch (BadLocationException e) {
            JOptionPane.showMessageDialog(frame, "Ошибка при вставке даты и времени: неверная позиция.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Ошибка при вставке даты и времени.");
        }
    }

    private static void changeTextColor() {
        Color newColor = JColorChooser.showDialog(frame, "Выберите цвет текста", editorPane.getForeground());
        if (newColor != null) {
            int start = editorPane.getSelectionStart();
            int end = editorPane.getSelectionEnd();

            if (start != end) {
                StyledDocument doc = (StyledDocument) editorPane.getDocument();
                SimpleAttributeSet attr = new SimpleAttributeSet();
                StyleConstants.setForeground(attr, newColor);
                doc.setCharacterAttributes(start, end - start, attr, false);
            } else {
                JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения цвета.");
            }
        }
    }

    private static void changeBackgroundColor() {
        Color newColor = JColorChooser.showDialog(frame, "Выберите цвет фона", editorPane.getBackground());
        if (newColor != null) {
            int start = editorPane.getSelectionStart();
            int end = editorPane.getSelectionEnd();

            if (start != end) { // Если есть выделенный текст
                StyledDocument doc = (StyledDocument) editorPane.getDocument();
                SimpleAttributeSet attr = new SimpleAttributeSet();
                StyleConstants.setBackground(attr, newColor);

                // Применяем атрибуты к выделенному тексту
                doc.setCharacterAttributes(start, end - start, attr, false);
            } else {
                // Если текст не выделен, меняем фон всего документа
                editorPane.setBackground(newColor);
            }
        }
    }

    static void addWordLikeMenu() {
        JTabbedPane tabbedPane = new JTabbedPane();

        // Панель для вкладки "Файл"
        JPanel filePanel = new JPanel(new GridLayout(2, 3, 10, 10));
        filePanel.add(createMenuButton("Создать", e -> createNewFile()));
        filePanel.add(createMenuButton("Открыть", e -> openFile()));
        filePanel.add(createMenuButton("Сохранить", e -> saveFile(true)));
        filePanel.add(createMenuButton("Сохранить как", e -> saveAsFile()));
        filePanel.add(createMenuButton("Печать", e -> printFile()));
        filePanel.add(createMenuButton("Закрыть", e -> closeFile()));
        tabbedPane.addTab("Файл", filePanel);

        // Панель для вкладки "Главная"
        JPanel homePanel = new JPanel(new FlowLayout());

        // Выпадающий список для выбора шрифта
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontComboBox = new JComboBox<>(fonts);
        fontComboBox.setSelectedItem("Times New Roman");

        fontComboBox.addActionListener(e -> {
            String selectedFont = (String) fontComboBox.getSelectedItem();
            applyFont(selectedFont, -1);
        });

        // Выпадающий список для выбора размера шрифта
        Integer[] fontSizes = {8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30};
        JComboBox<Integer> sizeComboBox = new JComboBox<>(fontSizes);
        sizeComboBox.setSelectedItem(14);

        sizeComboBox.addActionListener(e -> {
            int selectedSize = (int) sizeComboBox.getSelectedItem();
            applyFont(null, selectedSize);
        });

        // Добавляем элементы на панель
        JPanel fontPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fontPanel.add(fontComboBox);
        fontPanel.add(sizeComboBox);
        homePanel.add(fontPanel);

        // Добавление слушателя для синхронизации выбора шрифта и размера с выделенным текстом
        editorPane.addCaretListener(e -> {
            int start = editorPane.getSelectionStart();
            int end = editorPane.getSelectionEnd();

            if (start != end) {
                StyledDocument doc = (StyledDocument) editorPane.getDocument();

                String currentFont = null;
                int currentFontSize = -1;
                boolean multipleFonts = false;
                boolean multipleSizes = false;

                for (int i = start; i < end; i++) {
                    Element element = doc.getCharacterElement(i);
                    AttributeSet attributes = element.getAttributes();

                    // Проверка шрифта
                    String font = StyleConstants.getFontFamily(attributes);
                    if (currentFont == null) {
                        currentFont = font;
                    } else if (!currentFont.equals(font)) {
                        multipleFonts = true;
                    }

                    // Проверка размера шрифта
                    int fontSize = StyleConstants.getFontSize(attributes);
                    if (currentFontSize == -1) {
                        currentFontSize = fontSize;
                    } else if (currentFontSize != fontSize) {
                        multipleSizes = true;
                    }

                    if (multipleFonts && multipleSizes) {
                        break; // Дальнейшие проверки не нужны, так как уже выявлены разные значения
                    }
                }

                // Обновление значений в JComboBox
                if (multipleFonts) {
                    fontComboBox.setSelectedItem(null); // Показать, что шрифты различаются
                } else {
                    fontComboBox.setSelectedItem(currentFont);
                }

                if (multipleSizes) {
                    sizeComboBox.setSelectedItem(null); // Показать, что размеры различаются
                } else {
                    sizeComboBox.setSelectedItem(currentFontSize);
                }
            }
        });
        // Добавляем остальные кнопки форматирования
        homePanel.add(createMenuButton("Формат по образцу", e -> copyFormat()));
        homePanel.add(createMenuButton("Применить формат", e -> applyFormat()));
        homePanel.add(createMenuButton("Полужирный", e -> toggleBold()));
        homePanel.add(createMenuButton("Курсив", e -> toggleItalic()));
        homePanel.add(createMenuButton("Подчеркнутый", e -> toggleUnderline()));
        homePanel.add(createMenuButton("Зачеркнутый", e -> toggleStrikethrough()));
        homePanel.add(createMenuButton("Подстрочный знак", e -> toggleSubscript()));
        homePanel.add(createMenuButton("Надстрочный знак", e -> toggleSuperscript()));
        homePanel.add(createMenuButton("Цвет шрифта", e -> changeTextColor()));
        homePanel.add(createMenuButton("Цвет фона", e -> changeBackgroundColor()));
        homePanel.add(createMenuButton("Очистить все форматирования", e -> clearFormatting()));
        homePanel.add(createMenuButton("Регистр", e -> toggleCase()));
        homePanel.add(createMenuButton("Уменьшить размер шрифта", e -> decreaseFontSize()));
        homePanel.add(createMenuButton("Увеличить размер шрифта", e -> increaseFontSize()));
        homePanel.add(createMenuButton("Маркеры", e -> toggleBullets()));
        homePanel.add(createMenuButton("Нумерация", e -> toggleNumbering()));
        homePanel.add(createMenuButton("Многоуровневая нумерация", e -> toggleMultilevelNumbering()));
        homePanel.add(createMenuButton("Заливка", e -> applyBackgroundFill()));

        tabbedPane.addTab("Главная", homePanel);

        // Панель для вкладки "Изменить" с GridLayout
        JPanel editPanel = new JPanel(new GridLayout(3, 4, 10, 10));
//        editPanel.add(createMenuButton("Отменить"));
        editPanel.add(createActionButton(new DefaultEditorKit.CutAction(), "Вырезать"));
        editPanel.add(createActionButton(new DefaultEditorKit.CopyAction(), "Копировать"));
        editPanel.add(createActionButton(new DefaultEditorKit.PasteAction(), "Вставить"));
//        editPanel.add(createMenuButton("Удалить"));
        editPanel.add(createMenuButton("Найти", e -> findText()));
        editPanel.add(createMenuButton("Найти далее", e -> findNext()));
        editPanel.add(createMenuButton("Найти ранее", e -> findPrevious()));
        editPanel.add(createMenuButton("Заменить", e -> replaceText()));
        editPanel.add(createMenuButton("Перейти", e -> goToLine()));
        editPanel.add(createMenuButton("Выбрать все", e -> selectAllText()));
        editPanel.add(createMenuButton("Дата и время", e -> insertDateTime()));
        tabbedPane.addTab("Изменить", editPanel);

//        Панель для вкладки "Настройки"
//        JPanel settingsPanel = new JPanel();
//        tabbedPane.addTab("Настройки", settingsPanel);

        frame.add(tabbedPane, BorderLayout.NORTH);
    }

    private static JButton createMenuButton(String text, ActionListener action) {
        JButton button = new JButton(text);
        button.addActionListener(action);
        return button;
    }

    private static JButton createActionButton(Action action, String text) {
        JButton button = new JButton(action);
        button.setText(text); // Устанавливаем текст кнопки
        return button;
    }

    private static void copyFormat() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();
        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            AttributeSet attributes = doc.getCharacterElement(start).getAttributes();
            copiedAttributes = new SimpleAttributeSet(attributes);
            JOptionPane.showMessageDialog(frame, "Формат скопирован.");
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для копирования формата.");
        }
    }

    private static void applyFormat() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();
        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            doc.setCharacterAttributes(start, end - start, copiedAttributes, false);
            JOptionPane.showMessageDialog(frame, "Формат применен.");
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для применения формата.");
        }
    }


    private static void toggleBold() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Проверяем, является ли выделенный текст уже полужирным
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            boolean isBold = StyleConstants.isBold(attributes);

            // Устанавливаем или снимаем полужирный стиль
            StyleConstants.setBold(attr, !isBold);
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения стиля.");
        }
    }

    private static void toggleItalic() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Проверяем, является ли выделенный текст уже курсивным
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            boolean isItalic = StyleConstants.isItalic(attributes);

            // Устанавливаем или снимаем курсивный стиль
            StyleConstants.setItalic(attr, !isItalic);
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения стиля.");
        }
    }

    private static void toggleUnderline() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Проверяем, является ли выделенный текст уже подчеркнутым
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            boolean isUnderlined = StyleConstants.isUnderline(attributes);

            // Устанавливаем или снимаем подчеркивание
            StyleConstants.setUnderline(attr, !isUnderlined);
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения стиля.");
        }
    }

    private static void toggleStrikethrough() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Проверяем, является ли выделенный текст уже зачеркнутым
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            boolean isStrikethrough = StyleConstants.isStrikeThrough(attributes);

            // Устанавливаем или снимаем зачеркивание
            StyleConstants.setStrikeThrough(attr, !isStrikethrough);
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения стиля.");
        }
    }

    private static void toggleSubscript() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Проверяем, является ли выделенный текст уже подстрочным
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            boolean isSubscript = StyleConstants.isSubscript(attributes);

            // Устанавливаем или снимаем подстрочный стиль
            StyleConstants.setSubscript(attr, !isSubscript);
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения стиля.");
        }
    }

    private static void toggleSuperscript() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Проверяем, является ли выделенный текст уже надстрочным
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            boolean isSuperscript = StyleConstants.isSuperscript(attributes);

            // Устанавливаем или снимаем надстрочный стиль
            StyleConstants.setSuperscript(attr, !isSuperscript);
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения стиля.");
        }
    }

    private static void clearFormatting() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet(); // Пустой набор атрибутов
            doc.setCharacterAttributes(start, end - start, attr, true);
            JOptionPane.showMessageDialog(frame, "Форматирование очищено.");
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для очистки форматирования.");
        }
    }

    private static void toggleCase() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            try {
                // Получаем выделенный текст
                String selectedText = doc.getText(start, end - start);

                // Проверяем, содержит ли выделение только заглавные буквы
                boolean isUpperCase = selectedText.equals(selectedText.toUpperCase());

                // Преобразуем текст в противоположный регистр
                String transformedText = isUpperCase ? selectedText.toLowerCase() : selectedText.toUpperCase();

                // Заменяем выделенный текст новым
                doc.remove(start, end - start);
                doc.insertString(start, transformedText, null);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Ошибка при изменении регистра.");
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения регистра.");
        }
    }

    private static void decreaseFontSize() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Получаем текущий размер шрифта выделенного текста
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            int currentFontSize = StyleConstants.getFontSize(attributes);

            // Уменьшаем размер шрифта на 2 пункта, но не менее 2 пунктов
            int newFontSize = Math.max(currentFontSize - 2, 2);
            StyleConstants.setFontSize(attr, newFontSize);

            // Применяем новый размер шрифта к выделенному тексту
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения размера шрифта.");
        }
    }

    private static void increaseFontSize() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();

            // Получаем текущий размер шрифта выделенного текста
            Element element = doc.getCharacterElement(start);
            AttributeSet attributes = element.getAttributes();
            int currentFontSize = StyleConstants.getFontSize(attributes);

            // Увеличиваем размер шрифта на 2 пункта
            int newFontSize = currentFontSize + 2;
            StyleConstants.setFontSize(attr, newFontSize);

            // Применяем новый размер шрифта к выделенному тексту
            doc.setCharacterAttributes(start, end - start, attr, false);
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для изменения размера шрифта.");
        }
    }

    private static void toggleBullets() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            try {
                // Получаем выделенный текст
                String selectedText = doc.getText(start, end - start);
                AttributeSet originalAttributes = doc.getCharacterElement(start).getAttributes();

                // Разделяем текст на строки
                String[] lines = selectedText.split("\n");

                // Проверяем, все ли строки уже имеют маркер
                boolean allLinesMarked = true;
                for (String line : lines) {
                    if (!line.trim().startsWith("•")) {
                        allLinesMarked = false;
                        break;
                    }
                }

                // Добавляем или удаляем маркеры
                StringBuilder modifiedText = new StringBuilder();
                for (String line : lines) {
                    if (allLinesMarked) {
                        // Если все строки с маркерами, удаляем их
                        modifiedText.append(line.replaceFirst("•\\s*", "")).append("\n");
                    } else {
                        // Иначе добавляем маркер
                        modifiedText.append("• ").append(line).append("\n");
                    }
                }

                // Удаляем выделенный текст и вставляем обновленный с сохранением атрибутов
                doc.remove(start, end - start);
                doc.insertString(start, modifiedText.toString(), originalAttributes);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Ошибка при добавлении маркеров.");
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для добавления маркеров.");
        }
    }

    private static void toggleNumbering() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            try {
                // Получаем выделенный текст
                String selectedText = doc.getText(start, end - start);
                AttributeSet originalAttributes = doc.getCharacterElement(start).getAttributes();

                // Разделяем текст на строки
                String[] lines = selectedText.split("\n");

                // Проверяем, все ли строки уже имеют нумерацию
                boolean allLinesNumbered = true;
                for (String line : lines) {
                    if (!line.trim().matches("^\\d+\\.\\s.*")) {
                        allLinesNumbered = false;
                        break;
                    }
                }

                // Добавляем или удаляем номера
                StringBuilder modifiedText = new StringBuilder();
                int lineNumber = 1;
                for (String line : lines) {
                    if (allLinesNumbered) {
                        // Если все строки с номерами, удаляем их
                        modifiedText.append(line.replaceFirst("^\\d+\\.\\s*", "")).append("\n");
                    } else {
                        // Иначе добавляем номер
                        modifiedText.append(lineNumber).append(". ").append(line).append("\n");
                        lineNumber++;
                    }
                }

                // Удаляем выделенный текст и вставляем обновленный
                doc.remove(start, end - start);
                doc.insertString(start, modifiedText.toString(), originalAttributes);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Ошибка при добавлении нумерации.");
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для добавления нумерации.");
        }
    }

    private static void toggleMultilevelNumbering() {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();

        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            try {
                // Получаем выделенный текст
                String selectedText = doc.getText(start, end - start);
                AttributeSet originalAttributes = doc.getCharacterElement(start).getAttributes();

                // Разделяем текст на строки
                String[] lines = selectedText.split("\n");

                // Проверяем, все ли строки уже имеют многоуровневую нумерацию
                boolean allLinesNumbered = true;
                for (String line : lines) {
                    if (!line.trim().matches("^\\d+(\\.\\d+)*\\.\\s+.*")) {
                        allLinesNumbered = false;
                        break;
                    }
                }

                // Добавляем или удаляем многоуровневую нумерацию
                StringBuilder modifiedText = new StringBuilder();
                for (String line : lines) {
                    int indentLevel = countLeadingTabsOrSpaces(line);
                    if (allLinesNumbered) {
                        // Если все строки уже пронумерованы, удаляем номера
                        modifiedText.append(line.replaceFirst("^\\d+(\\.\\d+)*\\.\\s*", "")).append("\n");
                    } else {
                        // Генерируем номер для текущего уровня отступа
                        String numbering = generateNumbering(indentLevel);
                        modifiedText.append(numbering).append(" ").append(line.trim()).append("\n");
                    }
                }

                // Удаляем выделенный текст и вставляем обновленный
                doc.remove(start, end - start);
                doc.insertString(start, modifiedText.toString(), originalAttributes);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Ошибка при добавлении многоуровневой нумерации.");
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для добавления многоуровневой нумерации.");
        }
    }

    // Функция для подсчета количества начальных пробелов или табуляций
    private static int countLeadingTabsOrSpaces(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) {
            count++;
        }
        return count;
    }

    // Функция для генерации нумерации в зависимости от уровня отступа
    private static String generateNumbering(int level) {
        StringBuilder numbering = new StringBuilder();
        for (int i = 1; i <= level + 1; i++) {
            numbering.append(i).append(".");
        }
        return numbering.toString();
    }

    private static void applyBackgroundFill() {
        Color newColor = JColorChooser.showDialog(frame, "Выберите цвет заливки", Color.YELLOW);
        if (newColor != null) {
            int start = editorPane.getSelectionStart();
            int end = editorPane.getSelectionEnd();

            if (start != end) {
                StyledDocument doc = (StyledDocument) editorPane.getDocument();
                SimpleAttributeSet attr = new SimpleAttributeSet();

                // Устанавливаем цвет фона
                StyleConstants.setBackground(attr, newColor);
                doc.setCharacterAttributes(start, end - start, attr, false);
            } else {
                JOptionPane.showMessageDialog(frame, "Пожалуйста, выделите текст для применения заливки.");
            }
        }
    }

    // Метод для применения шрифта и/или размера к выделенному тексту
    private static void applyFont(String fontFamily, int fontSize) {
        int start = editorPane.getSelectionStart();
        int end = editorPane.getSelectionEnd();
        if (start != end) {
            StyledDocument doc = (StyledDocument) editorPane.getDocument();
            MutableAttributeSet attributes = new SimpleAttributeSet();

            if (fontFamily != null) {
                StyleConstants.setFontFamily(attributes, fontFamily);
            }
            if (fontSize != -1) {
                StyleConstants.setFontSize(attributes, fontSize);
            }

            doc.setCharacterAttributes(start, end - start, attributes, false);
        }
    }

    private static void loadSettings() {
        try (InputStream input = new FileInputStream("config.properties")) {
            settings.load(input);
        } catch (IOException e) {
            System.out.println("Не удалось загрузить настройки, используются значения по умолчанию.");
        }
    }

    static void addToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.add(createMenuButton("Создать", e -> createNewFile()));
        toolBar.add(createMenuButton("Открыть", e -> openFile()));
        toolBar.add(createMenuButton("Сохранить", e -> saveFile(false)));
        toolBar.add(createMenuButton("Сохранить как", e -> saveAsFile()));
        toolBar.add(createMenuButton("Печать", e -> printFile()));
        editorPane.getDocument().addUndoableEditListener(undoManager);
        toolBar.add(createMenuButton("Отменить", e -> {
            if (undoManager.canUndo()) undoManager.undo();
        }));
        toolBar.add(createMenuButton("Вернуть", e -> {
            if (undoManager.canRedo()) undoManager.redo();
        }));
        toolBar.add(createMenuButton("Темная тема", e -> toggleTheme()));
        frame.add(toolBar, BorderLayout.NORTH);
    }

    private static void toggleTheme() {
        Color backgroundColor = editorPane.getBackground().equals(Color.BLACK) ? Color.WHITE : Color.BLACK;
        Color textColor = editorPane.getForeground().equals(Color.WHITE) ? Color.BLACK : Color.WHITE;
        editorPane.setBackground(backgroundColor);
        editorPane.setForeground(textColor);
    }

    private static void addContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();
        contextMenu.add(createMenuItem("Копировать", e -> editorPane.copy()));
        contextMenu.add(createMenuItem("Вставить", e -> editorPane.paste()));
        contextMenu.add(createMenuItem("Вырезать", e -> editorPane.cut()));
        contextMenu.add(createMenuItem("Выбрать все", e -> editorPane.selectAll()));
        editorPane.setComponentPopupMenu(contextMenu);
    }
}