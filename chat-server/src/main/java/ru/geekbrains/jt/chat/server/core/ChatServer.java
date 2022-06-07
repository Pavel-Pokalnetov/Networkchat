package ru.geekbrains.jt.chat.server.core;

import ru.geekbrains.jt.chat.common.Messages;
import ru.geekbrains.jt.network.ServerSocketThread;
import ru.geekbrains.jt.network.ServerSocketThreadListener;
import ru.geekbrains.jt.network.SocketThread;
import ru.geekbrains.jt.network.SocketThreadListener;

import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServer implements ServerSocketThreadListener, SocketThreadListener {
    private final int SERVER_SOCKET_TIMEOUT = 2000;
    private final DateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss: ");
    private Vector<SocketThread> clients = new Vector<>();
    ExecutorService threadPoolService;

    int counter = 0;
    ServerSocketThread server;
    ChatServerListener listener;

    public ChatServer(ChatServerListener listener) {
        this.listener = listener;
    }

    public void start(int port) {
        if (server != null && server.isAlive()) {
            putLog("Server already started");
        } else {
            server = new ServerSocketThread(this, "Chat server " + counter++, port, SERVER_SOCKET_TIMEOUT);
        }
    }

    public void stop() {
        if (server == null || !server.isAlive()) {
            putLog("Server is not running");
        } else {
            this.threadPoolService.shutdownNow();
            server.interrupt();
        }
    }

    private void putLog(String msg) {
        msg = DATE_FORMAT.format(System.currentTimeMillis()) +
                Thread.currentThread().getName() +
                ": " + msg;
        listener.onChatServerMessage(msg);

    }

    /**
     * Server socket thread methods
     */

    @Override
    public void onServerStart(ServerSocketThread thread) {
        putLog("Server thread started");
        SqlClient.connect();
        this.threadPoolService = Executors.newCachedThreadPool();
    }

    @Override
    public void onServerStop(ServerSocketThread thread) {
        putLog("Server thread stopped");
        //this.threadPoolService.shutdownNow();
        SqlClient.disconnect();
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).close();
        }
    }

    @Override
    public void onServerSocketCreated(ServerSocketThread t, ServerSocket s) {
        putLog("Server socket created");
    }

    @Override
    public void onServerSoTimeout(ServerSocketThread t, ServerSocket s) {

    }

    @Override
    public void onSocketAccepted(ServerSocketThread t, ServerSocket s, Socket client) {
        putLog("Client connected");
        String name = "SocketThread" + client.getInetAddress() + ": " + client.getPort();
       threadPoolService.execute(new ClientThread(this, name, client));
    }

    @Override
    public void onServerException(ServerSocketThread t, Throwable e) {
        e.printStackTrace();
    }

    /**
     * Socket Thread listening
     */

    @Override
    public synchronized void onSocketStart(SocketThread t, Socket s) {
        putLog("Client connected");
    }

    @Override
    public synchronized void onSocketStop(SocketThread t) {
        ClientThread client = (ClientThread) t;
        clients.remove(client);
        if (client.isAuthorized() && !client.isReconnecting()) {
            sendToAllAuthorized(Messages.getTypeBroadcast("Server", client.getNickname() + " disconnected"));
        }
        sendToAllAuthorized(Messages.getUserList(getUsers()));
    }

    @Override
    public synchronized void onSocketReady(SocketThread t, Socket socket) {
        putLog("Client is ready");
        clients.add(t);
    }

    @Override
    public synchronized void onReceiveString(SocketThread t, Socket s, String msg) {
        ClientThread client = (ClientThread) t;
        if (client.isAuthorized()) {
            handleAuthMsg(client, msg);
        } else {
            handleNonAuthMsg(client, msg);
        }
    }

    private void handleAuthMsg(ClientThread client, String msg) {
        String[] arr = msg.split(Messages.DELIMITER);
        String msgType = arr[0];
        switch (msgType) {
            case Messages.SETPASSWORD:
                setUserPassword(client, arr[1], arr[2]);
                break;
            case Messages.SETNICK:
                setUserNick(client, arr[1], arr[2]);
                break;
            case Messages.UNICAST:
                sendToUser(arr[2], Messages.getMsgUnicast(client.getNickname(), arr[3]));
                break;
            case Messages.USER_BROADCAST:
                sendToAllAuthorized(Messages.getTypeBroadcast(client.getNickname(), arr[1]));
                break;
            default:
                client.msgFormatError(msg);
        }
    }

    private void setUserPassword(ClientThread client, String login, String password) {
        //меняем пароль;
        System.out.println(login + "||" + password);
        if (SqlClient.setUserPassword(login, password)) {
            client.sendMessage(Messages.getSrvUnicast("пароль успешно изменен"));
            client.sendMessage(Messages.getSrvUnicast("переподключитесь к серверу с новым паролем"));
        } else {
            client.sendMessage(Messages.getSrvUnicast("не удалось изменить пароль"));
        }
    }

    private void setUserNick(ClientThread client, String login, String nickname) {
        //меняем ник
        if (SqlClient.setUserNickName(login, nickname)) {
            client.sendMessage(Messages.getSrvUnicast("ник успешно изменен"));
            client.sendMessage(Messages.getSrvUnicast("переподключитесь к серверу"));
        } else {
            client.sendMessage(Messages.getSrvUnicast("не удалось изменить ник"));
        }
    }

    private void sendToAllAuthorized(String msg) {
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if (!client.isAuthorized()) continue;
            client.sendMessage(msg);
        }
    }

    private void sendToUser(String desClient, String msg) {
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if (client.getNickname().equals(desClient)) {
                client.sendMessage(msg);
                return;
            }
        }
        return;
    }

    private void handleNonAuthMsg(ClientThread client, String msg) {
        String[] arr = msg.split(Messages.DELIMITER);
        if (arr.length == 4 & arr[0].equals(Messages.REGISTRATION)) {
            //регистрация нового клиента
            String nickname = arr[1];
            String login = arr[2];
            if (SqlClient.checkLogin(login)) {
                //login уже занят
                client.sendMessage(Messages.getSrvUnicast("Этот логин занят"));
                return;
            }
            String password = arr[3];
            if (password.equals("")) {
                client.sendMessage(Messages.getSrvUnicast("Пароль не может быть пустым"));
                return;
            }
            if (SqlClient.addLogin(nickname, login, password)) {
                //успешная регистрация нового клиента
                client.sendMessage(Messages.REGISTRATION_OK);
                client.authAccept(nickname);
                client.sendMessage(Messages.getSrvUnicast("Вы зарегистрированы!"));
                client.sendMessage(Messages.getSrvUnicast("логин: " + login));
                client.sendMessage(Messages.getSrvUnicast("пароль: " + password));
                client.sendMessage(Messages.getSrvUnicast("Ва можете авторизоваться под новым логином"));
                client.reconnect();
                return;
            } else {
                client.sendMessage(Messages.getSrvUnicast("Регистрация не удалась"));
                return;
            }
        }

        if (arr.length != 3 || !arr[0].equals(Messages.AUTH_REQUEST)) {
            //косячный мессадж
            client.msgFormatError(msg);
            return;
        }

        String login = arr[1];
        String password = arr[2];
        String nickname = SqlClient.getNick(login, password);
        if (nickname == null) {
            putLog("Invalid login attempt " + login);
            client.authFail();
            client.reconnect();
            return;
        } else {
            ClientThread oldClient = findClientByNickname(nickname);
            client.authAccept(nickname);
            if (oldClient == null) {
                sendToAllAuthorized(Messages.getTypeBroadcast("Server", nickname + " connected."));
            } else {
                oldClient.reconnect();
                clients.remove(oldClient);
            }
        }
        sendToAllAuthorized(Messages.getUserList(getUsers()));
    }

    @Override
    public synchronized void onSocketException(SocketThread t, Throwable e) {
        e.printStackTrace();
    }

    private String getUsers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if (!client.isAuthorized()) continue;
            sb.append(client.getNickname()).append(Messages.DELIMITER);
        }
        return sb.toString();
    }

    private synchronized ClientThread findClientByNickname(String nickname) {
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if (!client.isAuthorized()) continue;
            if (client.getNickname().equals(nickname))
                return client;
        }
        return null;
    }
}