package co.smartreceipts.android.purchases.consumption;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import com.google.common.base.Preconditions;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;

import co.smartreceipts.android.purchases.model.InAppPurchase;
import co.smartreceipts.android.purchases.model.PurchaseFamily;
import co.smartreceipts.android.purchases.model.Subscription;
import io.reactivex.Completable;

class SubscriptionInAppPurchaseConsumer implements InAppPurchaseConsumer<Subscription> {

    private static final String KEY_CONSUMED_SUBSCRIPTION_SET = "key_consumed_subscription_set";
    private static final String FORMAT_KEY_PURCHASE_FAMILY = "key_%s_purchase_family";

    private final SharedPreferences sharedPreferences;

    @Inject
    public SubscriptionInAppPurchaseConsumer(@NonNull Context context) {
        this(PreferenceManager.getDefaultSharedPreferences(context));
    }

    @VisibleForTesting
    public SubscriptionInAppPurchaseConsumer(@NonNull SharedPreferences preferences) {
        this.sharedPreferences = Preconditions.checkNotNull(preferences);
    }

    @Override
    public boolean isConsumed(@NonNull Subscription managedProduct, @NonNull PurchaseFamily purchaseFamily) {
        final Set<String> consumedSubscriptionSkuSet = sharedPreferences.getStringSet(KEY_CONSUMED_SUBSCRIPTION_SET, Collections.emptySet());
        for (final String sku : consumedSubscriptionSkuSet) {
            if (managedProduct.getInAppPurchase().equals(InAppPurchase.from(sku))) {
                final String family = sharedPreferences.getString(getPurchaseFamilyKey(sku), "");
                if (purchaseFamily.name().equals(family)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Completable consumePurchase(@NonNull Subscription managedProduct, @NonNull PurchaseFamily purchaseFamily) {
        return Completable.fromAction(() -> {
            final Set<String> consumedSubscriptionSkuSet = new HashSet<>(sharedPreferences.getStringSet(KEY_CONSUMED_SUBSCRIPTION_SET, Collections.emptySet()));
            final String sku = managedProduct.getInAppPurchase().getSku();
            if (!consumedSubscriptionSkuSet.contains(sku)) {
                consumedSubscriptionSkuSet.add(sku);
                final SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putStringSet(KEY_CONSUMED_SUBSCRIPTION_SET, consumedSubscriptionSkuSet);
                editor.putString(getPurchaseFamilyKey(sku), purchaseFamily.name());
                editor.apply();
            }
        });
    }

    @NonNull
    private String getPurchaseFamilyKey(@NonNull String sku) {
        return String.format(Locale.US, FORMAT_KEY_PURCHASE_FAMILY, sku);
    }
}
