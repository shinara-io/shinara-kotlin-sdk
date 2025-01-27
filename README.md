# Shinara Kotlin SDK

This SDK provides a simple interface for integrating [Shinara](https://shinara.io/) functionality into your Kotlin application.

## Installation
Add it in your root build.gradle at the end of repositories:

```
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        ..
        maven { url 'https://jitpack.io' }
	}
}
```

Add the dependency
```
dependencies {
    implementation('com.github.shinara-io:shinara-kotlin-sdk:1.0.6')
}
```

## Usage

### Initialize Client
Initializes Shinara SDK and monitors In App Purchases to Attribute Conversion

```kotlin
ShinaraSDK.instance.initialize(applicationContext, "API_KEY")
```

### Validate Referral Code
Validates Affiliate's Referral Code
Note: Call `validateReferralCode` before In App Purchase for successful Attribution linking of Purchase and Affiliate

```kotlin
ShinaraSDK.instance.validateReferralCode("REFERRAL_CODE")
```

### Attribute Purchase
To attribute a purchase. Recommended to call this after successful in app purchase. Shinara will handle logic to only attribute purchase coming from a referral code

```kotlin
ShinaraSDK.instance.attributePurchase(
    playPurchase.products.firstOrNull() ?: "Unknown Product",
    playPurchase.orderId.orEmpty(),
    playPurchase.purchaseToken,
)
```

### Register a user (Optional)
By default, Shinara creates a new random userId and assign it to a conversion. Use `registerUser` if you want to use your own internal user id.

```kotlin
ShinaraSDK.instance.registerUser("INTERNAL_USER_ID", "EMAIL", "NAME", "PHONE")
```
