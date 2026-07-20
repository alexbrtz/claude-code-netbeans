package org.openbeans.claude.netbeans.ui;

import org.openbeans.claude.netbeans.ClaudeCodeStatusService;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.windows.TopComponent;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Docked panel showing MCP server status (running/port/PID/lock file/client)
 * with a manual restart action. Polls {@link ClaudeCodeStatusService} on a
 * Swing timer while open.
 */
@TopComponent.Description(
    preferredID = "ClaudeCodeStatusTopComponent",
    persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@ActionID(category = "Window", id = "org.openbeans.claude.netbeans.ui.ClaudeCodeStatusTopComponent")
@ActionReference(path = "Menu/Window", position = 1500)
@TopComponent.OpenActionRegistration(
    displayName = "#CTL_ClaudeCodeStatusAction",
    preferredID = "ClaudeCodeStatusTopComponent"
)
@Messages("CTL_ClaudeCodeStatusAction=Claude Code Status Panel")
public final class ClaudeCodeStatusTopComponent extends TopComponent {

    private static final int REFRESH_INTERVAL_MS = 2000;

    private final ClaudeCodeStatusService statusService;
    private final Timer refreshTimer;

    private JLabel serverValueLabel;
    private JLabel portValueLabel;
    private JLabel pidValueLabel;
    private JLabel lockFileValueLabel;
    private JLabel clientValueLabel;

    public ClaudeCodeStatusTopComponent() {
        statusService = Lookup.getDefault().lookup(ClaudeCodeStatusService.class);

        setName(org.openide.util.NbBundle.getMessage(ClaudeCodeStatusTopComponent.class, "CTL_ClaudeCodeStatusTopComponent"));
        setToolTipText(org.openide.util.NbBundle.getMessage(ClaudeCodeStatusTopComponent.class, "HINT_ClaudeCodeStatusTopComponent"));

        initComponents();
        refreshTimer = new Timer(REFRESH_INTERVAL_MS, e -> refresh());
    }

    private void initComponents() {
        setLayout(new java.awt.BorderLayout());

        JPanel content = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        serverValueLabel = addStatusRow(content, gbc, row++, "Server:");
        portValueLabel = addStatusRow(content, gbc, row++, "Port:");
        pidValueLabel = addStatusRow(content, gbc, row++, "PID:");
        lockFileValueLabel = addStatusRow(content, gbc, row++, "Lock file:");
        clientValueLabel = addStatusRow(content, gbc, row++, "Clients:");

        JButton restartButton = new JButton("Restart Server");
        restartButton.addActionListener(e -> {
            if (statusService != null) {
                restartButton.setEnabled(false);
                statusService.restartServer();
                Timer reEnable = new Timer(1500, ev -> restartButton.setEnabled(true));
                reEnable.setRepeats(false);
                reEnable.start();
            }
        });

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(10, 8, 4, 8);
        content.add(restartButton, gbc);

        add(content, java.awt.BorderLayout.NORTH);
    }

    private JLabel addStatusRow(JPanel content, GridBagConstraints gbc, int row, String labelText) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        content.add(new JLabel(labelText), gbc);

        JLabel valueLabel = new JLabel("-");
        gbc.gridx = 1;
        content.add(valueLabel, gbc);
        return valueLabel;
    }

    private void refresh() {
        if (statusService == null) {
            serverValueLabel.setText("Service not available");
            return;
        }

        boolean running = statusService.isServerRunning();
        serverValueLabel.setText(running ? "Running" : "Stopped");
        portValueLabel.setText(running ? String.valueOf(statusService.getServerPort()) : "-");
        pidValueLabel.setText(String.valueOf(statusService.getServerPid()));
        lockFileValueLabel.setText(statusService.isLockFileValid() ? "Valid" : "Invalid");
        int clientCount = statusService.getConnectedClientCount();
        clientValueLabel.setText(clientCount == 0 ? "None connected" : clientCount + " connected");
    }

    @Override
    public void componentOpened() {
        refresh();
        refreshTimer.start();
    }

    @Override
    public void componentClosed() {
        refreshTimer.stop();
    }
}
