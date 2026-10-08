package dev.openmap.live;

public record Greeting(String id, String world, String greeting, String greetingSub,
                       String farewell, String farewellSub) {

    public Greeting {
        id = id == null ? "" : id;
        world = world == null ? "" : world;
        greeting = greeting == null ? "" : greeting;
        greetingSub = greetingSub == null ? "" : greetingSub;
        farewell = farewell == null ? "" : farewell;
        farewellSub = farewellSub == null ? "" : farewellSub;
    }
}
