package org.netpreserve.warcbot.gui;

import org.netpreserve.warcbot.Config;
import org.netpreserve.warcbot.Crawl;
import org.netpreserve.warcbot.Warcbot;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.SQLException;

public class CrawlForm extends JFrame {
    private final Crawl crawl;
    private JTabbedPane tabbedPane;
    private JPanel contentPane;
    private JTable frontierTable;
    private JToolBar toolbar;
    private JButton playButton;
    private JButton pauseButton;
    private JButton configButton;
    private JTableHeader frontierTableHeader;

    public CrawlForm(Crawl crawl) {
        this.crawl = crawl;
        add(contentPane);
        setSize(700, 400);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        playButton.addActionListener(e -> crawl.unpause());
        pauseButton.addActionListener(e -> crawl.pause());
        configButton.addActionListener(e -> {
            var configEditor = new ConfigEditor(crawl.getConfig());
            configEditor.setVisible(true);
        });

        AbstractTableModel model = new AbstractTableModel() {
            String[] columns = new String[]{
                    "URL",
                    "Depth",
                    "State"
            };

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                return rowIndex + "x" + columnIndex;
            }

            @Override
            public String getColumnName(int column) {
                return columns[column];
            }

            @Override
            public int getRowCount() {
                return 1000;
            }

            @Override
            public int getColumnCount() {
                return columns.length;
            }
        };
        frontierTable.setModel(model);
        frontierTable.setRowSorter(new TableRowSorter(model));
    }

    private void createUIComponents() {
        // TODO: place custom component creation code here
    }

    public static void main(String[] args) throws SQLException, IOException {
        var crawl = new Warcbot(Paths.get("data"), new Config());
        SwingUtilities.invokeLater(() -> {
            var form = new CrawlForm(crawl);
            form.setVisible(true);
        });
    }

}
