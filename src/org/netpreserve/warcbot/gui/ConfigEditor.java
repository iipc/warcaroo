package org.netpreserve.warcbot.gui;

import org.netpreserve.warcbot.Config;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class ConfigEditor extends JFrame {
    private final Config config;

    private JTextField userAgentField;
    private JSpinner workersSpinner;
    private DefaultListModel<String> seedListModel;
    private DefaultListModel<String> includeListModel;
    private JTextField seedField;
    private JTextField includeField;
    private JList<String> seedsList;
    private JList<String> includesList;
    private JFileChooser fileChooser;

    public ConfigEditor(Config config) {
        this.config = config;
        initUI();
    }

    private void initUI() {
        setTitle("Crawl Configuration");
        setSize(700, 400);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top panel for user agent and workers
        JPanel topPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        userAgentField = new JTextField(20);
        userAgentField.setText(config.getUserAgent());
        JPanel userAgentPanel = new JPanel(new BorderLayout());
        userAgentPanel.add(new JLabel("User Agent: "), BorderLayout.WEST);
        userAgentPanel.add(userAgentField, BorderLayout.CENTER);
        topPanel.add(userAgentPanel);

        workersSpinner = new JSpinner(new SpinnerNumberModel(config.getWorkers(), 1, 100, 1));
        JPanel workersPanel = new JPanel(new BorderLayout());
        workersPanel.add(new JLabel("Workers: "), BorderLayout.WEST);
        workersPanel.add(workersSpinner, BorderLayout.CENTER);
        topPanel.add(workersPanel);

        // Center panel for seeds and includes list
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        seedListModel = new DefaultListModel<>();
        config.getSeeds().forEach(seedListModel::addElement);
        seedsList = new JList<>(seedListModel);
        JScrollPane seedScrollPane = new JScrollPane(seedsList);
        centerPanel.add(seedScrollPane);

        includeListModel = new DefaultListModel<>();
        config.getIncludes().stream().map(Pattern::pattern).forEach(includeListModel::addElement);
        includesList = new JList<>(includeListModel);
        JScrollPane includeScrollPane = new JScrollPane(includesList);
        centerPanel.add(includeScrollPane);

        // Bottom panel for inputs and action buttons
        JPanel inputPanel = new JPanel(new GridLayout(1, 8, 5, 5));
        seedField = new JTextField(20);
        JButton addSeedButton = new JButton("Add Seed");
        addSeedButton.addActionListener(this::addSeed);
        JButton removeSeedButton = new JButton("Remove Seed");
        removeSeedButton.addActionListener(this::removeSeed);

        includeField = new JTextField(20);
        JButton addIncludeButton = new JButton("Add Include Regex");
        addIncludeButton.addActionListener(this::addInclude);
        JButton removeIncludeButton = new JButton("Remove Include");
        removeIncludeButton.addActionListener(this::removeInclude);

        JButton loadButton = new JButton("Load");
        loadButton.addActionListener(this::loadConfig);
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(this::saveConfig);

        inputPanel.add(seedField);
        inputPanel.add(addSeedButton);
        inputPanel.add(removeSeedButton);
        inputPanel.add(includeField);
        inputPanel.add(addIncludeButton);
        inputPanel.add(removeIncludeButton);
        inputPanel.add(loadButton);
        inputPanel.add(saveButton);

        // Add panels to the frame
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(inputPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    private void addSeed(ActionEvent e) {
        String url = seedField.getText();
        if (!url.isEmpty()) {
            config.addSeed(url);
            seedListModel.addElement(url);
            seedField.setText("");
        }
    }

    private void removeSeed(ActionEvent e) {
        int selectedIndex = seedsList.getSelectedIndex();
        if (selectedIndex != -1) {
            seedListModel.remove(selectedIndex);
            config.getSeeds().remove(selectedIndex);
        }
    }

    private void addInclude(ActionEvent e) {
        String regex = includeField.getText();
        if (!regex.isEmpty()) {
            config.addInclude(regex);
            includeListModel.addElement(regex);
            includeField.setText("");
        }
    }

    private void removeInclude(ActionEvent e) {
        int selectedIndex = includesList.getSelectedIndex();
        if (selectedIndex != -1) {
            includeListModel.remove(selectedIndex);
            config.getIncludes().remove(selectedIndex); // This line may require conversion from Pattern to String
        }
    }

    private void loadConfig(ActionEvent e) {
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                config.load(Paths.get(fileChooser.getSelectedFile().toURI()));
                updateUserInterface();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void saveConfig(ActionEvent e) {
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                config.save(Paths.get(fileChooser.getSelectedFile().toURI()));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to save config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void updateUserInterface() {
        userAgentField.setText(config.getUserAgent());
        workersSpinner.setValue(config.getWorkers());
        seedListModel.removeAllElements();
        config.getSeeds().forEach(seedListModel::addElement);
        includeListModel.removeAllElements();
        config.getIncludes().stream().map(Pattern::pattern).forEach(includeListModel::addElement);
    }
    public static void main(String[] args) throws IOException {
        Config config = new Config();
        if (args.length > 0) {
            config.load(Path.of(args[0]));
        }
        EventQueue.invokeLater(() -> {
            ConfigEditor ex = new ConfigEditor(config);
            ex.setVisible(true);
        });
    }
}
