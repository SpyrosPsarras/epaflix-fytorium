# Compose, AndroidX, coroutines and OkHttp ship consumer R8 rules in their artifacts.
# Tokens and LoginAttempt use direct Kotlin access and org.json, not reflective serialization.
# No app-wide keep rule: unused icons and Compose code must remain shrinkable.
