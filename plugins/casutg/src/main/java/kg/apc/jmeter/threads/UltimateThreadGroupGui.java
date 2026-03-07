package kg.apc.jmeter.threads;

import kg.apc.charting.AbstractGraphRow;
import kg.apc.charting.DateTimeRenderer;
import kg.apc.charting.GraphPanelChart;
import kg.apc.charting.rows.GraphRowSumValues;
import kg.apc.jmeter.JMeterPluginsUtils;
import kg.apc.jmeter.gui.ButtonPanelAddCopyRemove;
import kg.apc.jmeter.gui.GuiBuilderHelper;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.gui.util.PowerTableModel;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.gui.AbstractThreadGroupGui;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class UltimateThreadGroupGui
        extends AbstractThreadGroupGui
        implements TableModelListener,
        CellEditorListener {

    public static final String WIKIPAGE = "UltimateThreadGroup";
    public static final String PROFILE_PROPERTY = "Profile";
    private static final Logger log = LoggerFactory.getLogger(UltimateThreadGroupGui.class);
    protected ConcurrentHashMap<String, AbstractGraphRow> model;
    private GraphPanelChart chart;
    public static final String[] columnIdentifiers = new String[]{
            "Start Threads Count", "Initial Delay, sec", "Startup Time, sec", "Hold Load For, sec", "Shutdown Time"
    };
    public static final Class[] columnClasses = new Class[]{
            String.class, String.class, String.class, String.class, String.class
    };
    public static final Integer[] defaultValues = new Integer[]{
            100, 0, 30, 60, 10
    };
    private LoopControlPanel loopPanel;
    protected PowerTableModel tableModel;
    protected JTable grid;
    public static final JTextField inpThreadsSchedule = new JTextField();
    protected ButtonPanelAddCopyRemove buttons;

    public UltimateThreadGroupGui() {
        super();
        init();
    }

    protected final void init() {
        JMeterPluginsUtils.addHelpLinkToPanel(this, WIKIPAGE);
        JPanel containerPanel = new VerticalPanel();

        containerPanel.add(createParamsPanel(), BorderLayout.NORTH);
        containerPanel.add(GuiBuilderHelper.getComponentWithMargin(createChart(), 2, 2, 0, 2), BorderLayout.CENTER);
        add(containerPanel, BorderLayout.CENTER);

        createControllerPanel();
    }

    private JPanel createParamsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Threads Schedule"));

        createTableModel();

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        JLabel inputLabel = new JLabel("Profile:");
        inputPanel.add(inputLabel, BorderLayout.WEST);
        inputPanel.add(inpThreadsSchedule, BorderLayout.CENTER);

        // ✅ Enter обрабатывает ВСЁ автоматически (JMeter var + обычный профиль)
        inpThreadsSchedule.addActionListener(e -> loadJMeterVariableOrProfile());

        // Таблица
        grid = new JTable(tableModel);
        grid.getDefaultEditor(String.class).addCellEditorListener(this);
        grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setPreferredSize(new Dimension(400, 120));

        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.add(inputPanel, BorderLayout.NORTH);
        centerPanel.add(scroll, BorderLayout.CENTER);
        panel.add(centerPanel, BorderLayout.CENTER);

        buttons = new ButtonPanelAddCopyRemove(grid, tableModel, defaultValues);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    private void loadJMeterVariable() {
        String fieldText = inpThreadsSchedule.getText().trim();
        if (fieldText.isEmpty()) return;

        try {
            String resolvedValue = resolveJMeterExpression(fieldText);

            if (resolvedValue != null && !resolvedValue.trim().isEmpty()) {
                inpThreadsSchedule.setText(fieldText);
                parseAndLoadProfile(resolvedValue);
            } else {
                inpThreadsSchedule.setText(fieldText);
                parseAndLoadProfile("spawn(0,0s,0s,0s,0s)");
            }
        } catch (Exception e) {
            log.error("❌ Failed to resolve '{}': {}", fieldText, e.getMessage());
        }
    }

    private String resolveJMeterExpression(String expression) {
        try {
            // 1. ✅ System properties (-J или user.properties)
            String varName = expression.substring(2, expression.length() - 1);
            String resolved = System.getProperty(varName);
            if (resolved != null) {
                return resolved;
            }

            // 2. ✅ JMeter properties через JMeterUtils
            resolved = JMeterUtils.getProperty(varName);
            if (resolved != null) {
                return resolved;
            }

            // 3. __P() функция - извлекаем имя свойства
            if (varName.startsWith("__P(") && varName.endsWith(")")) {
                String propName = varName.substring(4, varName.length() - 1);
                resolved = System.getProperty(propName);
                if (resolved != null) return resolved;
                resolved = JMeterUtils.getProperty(propName);
                if (resolved != null) return resolved;
            }

        } catch (Exception e) {
            log.debug("Failed to resolve JMeter expression: {}", expression, e);
        }

        return null;
    }


    private void loadJMeterVariableOrProfile() {
        String fieldText = inpThreadsSchedule.getText().trim();
        log.debug("Processing field: '{}'", fieldText);

        // Пробуем как JMeter выражение
        String resolved = resolveJMeterExpression(fieldText);

        if (resolved != null && !resolved.trim().isEmpty()) {
            // ✅ Успешно разрешили JMeter переменную
            inpThreadsSchedule.setText(fieldText);
            parseAndLoadProfile(resolved);
            log.info("✅ Loaded JMeter var '{}' → {}", fieldText, resolved);
        } else if (!fieldText.isEmpty()) {
            // ✅ Обычный профиль
            parseAndLoadProfile(fieldText);
            log.info("✅ Loaded profile: {}", fieldText);
        }
    }

    @Override
    public String getLabelResource() {
        return this.getClass().getSimpleName();
    }

    @Override
    public String getStaticLabel() {
        return JMeterPluginsUtils.prefixLabel("Ultimate Thread Group");
    }

    @Override
    public TestElement createTestElement() {
        UltimateThreadGroup tg = new UltimateThreadGroup();
        modifyTestElement(tg);
        tg.setComment(JMeterPluginsUtils.getWikiLinkText(WIKIPAGE));

        return tg;
    }

    @Override
    public void modifyTestElement(TestElement tg) {
        if (grid.isEditing()) {
            grid.getCellEditor().stopCellEditing();
        }

        if (tg instanceof UltimateThreadGroup) {
            UltimateThreadGroup utg = (UltimateThreadGroup) tg;

            // Сохраняем значение профиля
            utg.setProperty(PROFILE_PROPERTY, inpThreadsSchedule.getText());

            CollectionProperty rows = JMeterPluginsUtils.tableModelRowsToCollectionProperty(tableModel, UltimateThreadGroup.DATA_PROPERTY);
            utg.setData(rows);
            utg.setSamplerController((LoopController) loopPanel.createTestElement());
        }
        super.configureTestElement(tg);
    }

    @Override
    public void configure(TestElement tg) {
        super.configure(tg);
        UltimateThreadGroup utg = (UltimateThreadGroup) tg;

        if (tableModel == null) {
            createTableModel();
            if (grid != null) {
                grid.setModel(tableModel);
            }
        }

        tableModel.removeTableModelListener(this);

        // Загружаем таблицу из .jmx
        JMeterProperty threadValues = utg.getData();
        if (!(threadValues instanceof NullProperty)) {
            JMeterPluginsUtils.collectionPropertyToTableModelRows((CollectionProperty) threadValues, tableModel);
        }

        // Загружаем сохраненное значение поля (может содержать ${var})
        String savedProfile = utg.getPropertyAsString(PROFILE_PROPERTY, "");
        inpThreadsSchedule.setText(savedProfile);

        // Пробуем загрузить если это JMeter var
        if (savedProfile.startsWith("${") && savedProfile.endsWith("}")) {
            loadJMeterVariable();
        } else if (!savedProfile.trim().isEmpty()) {
            parseAndLoadProfile(savedProfile);
        }

        tableModel.addTableModelListener(this);

        TestElement te = (TestElement) tg.getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue();
        if (te != null) {
            loopPanel.configure(te);
        }
        buttons.checkDeleteButtonStatus();
        updateUI();
    }

    /**
     * Парсит профиль и заполняет таблицу данными
     * Формат: spawn(15,1s,1s,1s,1s) spawn(40,1s,3s,1s,2s)
     */
    private void parseAndLoadProfile(String profileText) {
        log.debug("Parsing profile: '{}'", profileText);

        if (tableModel == null) {
            log.error("❌ tableModel is null");
            return;
        }

        tableModel.removeTableModelListener(this);
        tableModel.clearData();

        try {
            String[] spawnGroups = profileText.split("\\s+");
            log.debug("Found {} spawn groups", spawnGroups.length);

            for (int i = 0; i < spawnGroups.length; i++) {
                String spawnGroup = spawnGroups[i].trim();
                log.debug("Processing spawn[{}]: '{}'", i, spawnGroup);

                if (!spawnGroup.startsWith("spawn(")) {
                    log.debug("Skipping invalid: {}", spawnGroup);
                    continue;
                }

                String params = spawnGroup.substring(6, spawnGroup.length() - 1).trim();
                String[] values = params.split(",");
                log.debug("Params: {}", Arrays.toString(values));

                if (values.length == 5) {
                    String[] parsed = {
                            parseNumber(values[0].trim()),
                            parseDuration(values[1].trim()),
                            parseDuration(values[2].trim()),
                            parseDuration(values[3].trim()),
                            parseDuration(values[4].trim())
                    };

                    log.debug("Parsed: {}", Arrays.toString(parsed));

                    // Добавляем только если НЕ пустые значения
                    if (!parsed[0].isEmpty() && parsed[0].matches("\\d+")) {
                        tableModel.addNewRow();
                        int rowIndex = tableModel.getRowCount() - 1;
                        for (int col = 0; col < 5; col++) {
                            tableModel.setValueAt(parsed[col], rowIndex, col);
                        }
                        log.debug("✅ Added row {}: {}", rowIndex, Arrays.toString(parsed));
                    } else {
                        log.warn("❌ Invalid threads count: '{}'", parsed[0]);
                    }
                } else {
                    log.warn("❌ Expected 5 params, got {}", values.length);
                }
            }
        } catch (Exception e) {
            log.error("❌ Parse error: {}", e.getMessage(), e);
        } finally {
            tableModel.addTableModelListener(this);
            SwingUtilities.invokeLater(() -> {
                grid.revalidate();
                grid.repaint();
                updateUI();
            });
        }
    }

    private String parseNumber(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[^0-9]", "");
    }

    private String parseDuration(String value) {
        if (value == null) return "";
        return value.trim().replace("s", "").replaceAll("[^0-9]", "");
    }

    @Override
    public void updateUI() {
        super.updateUI();

        if (tableModel != null) {
            UltimateThreadGroup utgForPreview = new UltimateThreadGroup();
            utgForPreview.setData(JMeterPluginsUtils.tableModelRowsToCollectionPropertyEval(tableModel, UltimateThreadGroup.DATA_PROPERTY));
            updateChart(utgForPreview);
        }
    }

    private void updateChart(UltimateThreadGroup tg) {
        tg.testStarted();
        model.clear();
        GraphRowSumValues row = new GraphRowSumValues();
        row.setColor(Color.RED);
        row.setDrawLine(true);
        row.setMarkerSize(AbstractGraphRow.MARKER_SIZE_NONE);
        row.setDrawThickLines(true);

        final HashTree hashTree = new HashTree();
        hashTree.add(new LoopController());
        JMeterThread thread = new JMeterThread(hashTree, null, null);

        long now = System.currentTimeMillis();

        // -1 because row.add(thread.getStartTime() - 1, 0)
        chart.setxAxisLabelRenderer(new DateTimeRenderer(DateTimeRenderer.HHMMSS, now - 1));
        chart.setForcedMinX(now);

        row.add(now, 0);

        // users in
        int numThreads = tg.getNumThreads();
        log.debug("Num Threads: " + numThreads);
        for (int n = 0; n < numThreads; n++) {
            thread.setThreadNum(n);
            thread.setThreadName(Integer.toString(n));
            tg.scheduleThread(thread, now);
            row.add(thread.getStartTime() - 1, 0);
            row.add(thread.getStartTime(), 1);
        }

        tg.testStarted();
        // users out
        for (int n = 0; n < tg.getNumThreads(); n++) {
            thread.setThreadNum(n);
            thread.setThreadName(Integer.toString(n));
            tg.scheduleThread(thread, now);
            row.add(thread.getEndTime() - 1, 0);
            row.add(thread.getEndTime(), -1);
        }

        model.put("Expected parallel users count", row);
        chart.invalidateCache();
        chart.repaint();
    }

    private void createControllerPanel() {
        loopPanel = new LoopControlPanel(false);
        LoopController looper = (LoopController) loopPanel.createTestElement();
        looper.setLoops(-1);
        looper.setContinueForever(true);
        loopPanel.configure(looper);
    }

    private Component createChart() {
        chart = new GraphPanelChart(false, true);
        model = new ConcurrentHashMap<>();
        chart.setRows(model);
        chart.getChartSettings().setDrawFinalZeroingLines(true);
        chart.setxAxisLabel("Elapsed time");
        chart.setYAxisLabel("Number of active threads");
        chart.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
        return chart;
    }

    public void tableChanged(TableModelEvent e) {
        updateUI();
    }

    private void createTableModel() {
        if (tableModel == null) {
            tableModel = new PowerTableModel(columnIdentifiers, columnClasses);
            tableModel.addTableModelListener(this);
        }
    }


    public void editingStopped(ChangeEvent e) {
        updateUI();
    }

    public void editingCanceled(ChangeEvent e) {
        // no action needed
    }

    @Override
    public void clearGui() {
        super.clearGui();
        tableModel.clearData();
    }
}