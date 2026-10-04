package ru.florestdev.messenger.model;

public class Contact {
    public String username;
    public boolean online;
    public boolean bot;

    public Contact(String username, boolean online, boolean bot) {
        this.username = username;
        this.online = online;
        this.bot = bot;
    }
}
