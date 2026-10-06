# :app-automotive

The Android Automotive OS app. The car's media center renders browsing and playback through the `MediaLibraryService` in `:playback:service`.
This module only adds the automotive manifest, app initialization and a settings activity that the car allows while parked, with online search and subscribing (`FeedSubscriber`), synchronization and mobile data options.
