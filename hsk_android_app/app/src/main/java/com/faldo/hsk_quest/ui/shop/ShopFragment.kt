package com.faldo.hsk_quest.ui.shop

import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.faldo.hsk_quest.HskQuestApp
import com.faldo.hsk_quest.MainActivity
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.databinding.FragmentShopBinding
import com.faldo.hsk_quest.util.Sfx
import com.faldo.hsk_quest.util.SpriteAnimator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Imperial Market Hub, Specialty Store Catalog, Item Detail Inspector, and Multi-Item Cart Checkout.
 */
class ShopFragment : Fragment() {

    private var _binding: FragmentShopBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShopViewModel by viewModels {
        val container = (requireActivity().application as HskQuestApp).container
        ShopViewModel.factory(
            shopRepository = container.shopRepository,
            playerRepository = container.playerRepository,
        )
    }

    private lateinit var adapter: ShopAdapter
    private var merchantAnimator: SpriteAnimator? = null
    private var detailSheet: BottomSheetDialog? = null
    private var detailAnimator: SpriteAnimator? = null

    private val loadingTips = listOf(
        "💡 Economy Tip: Higher-tier dishes like Peking Roast Duck restore 10.0 HP per Coin — 2.5x more efficient than Warm Rice Balls!",
        "💡 Combat Tip: Red Alchemical Potions heal 25%–100% of your Max HP directly inside quiz battles without wasting a turn!",
        "💡 Companion Tip: Keep your active Pet's Affection above 25% in the Sanctuary so its combat buff stays active!",
        "💡 Culinary Tip: Imperial Hotpot restores +1,040 Hero HP for 130 Coins (8.0 HP/Coin) — ideal for high-level adventurers!",
        "💡 Sanctuary Tip: Jade Bamboo Shoots restore a full +100 Pet Affection for only 70 Coins (1.43 ❤️/Coin)!",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentShopBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupHubAndNavigation()
        startMerchantAnimation()
        setupRecyclerView()
        observeViewModel()

        (activity as? MainActivity)?.attachScrollListener(binding.layoutShopHub)
    }

    private fun setupHubAndNavigation() {
        binding.btnShopBack.setOnClickListener {
            when (viewModel.screenMode.value) {
                ShopScreenMode.CHECKOUT -> viewModel.returnToHub()
                ShopScreenMode.STORE_CATALOG -> viewModel.returnToHub()
                ShopScreenMode.HUB -> {}
            }
        }

        binding.btnShopCart.setOnClickListener {
            viewModel.openCheckout()
        }

        binding.cardStorePotions.setOnClickListener {
            viewModel.openStore(ShopFilter.ELIXIR)
        }

        binding.cardStoreFood.setOnClickListener {
            viewModel.openStore(ShopFilter.FOOD)
        }

        binding.cardStorePetFood.setOnClickListener {
            viewModel.openStore(ShopFilter.PET_FOOD)
        }

        binding.cardStoreBag.setOnClickListener {
            viewModel.openStore(ShopFilter.BAG)
        }

        binding.btnFeedInSanctuary.setOnClickListener {
            findNavController().navigate(R.id.petFragment)
        }

        binding.btnProceedCheckout.setOnClickListener {
            viewModel.openCheckout()
        }

        binding.btnClearCart.setOnClickListener {
            viewModel.clearCart()
        }

        binding.btnConfirmCheckout.setOnClickListener {
            viewModel.checkoutCart()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.isCheckingOut.value) return
                    when (viewModel.screenMode.value) {
                        ShopScreenMode.CHECKOUT, ShopScreenMode.STORE_CATALOG -> {
                            viewModel.returnToHub()
                        }
                        ShopScreenMode.HUB -> {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
    }

    private fun startMerchantAnimation() {
        val b = _binding ?: return
        merchantAnimator?.stop()
        merchantAnimator = SpriteAnimator.startHorizontalStripAnimation(
            imageView = b.ivMerchantNpc,
            drawableRes = R.drawable.npc_mage_girl,
            cols = 10,
            fps = 8,
            scaleFactor = 3,
        )
    }

    private fun setupRecyclerView() {
        adapter = ShopAdapter(
            onBuyClicked = { item -> viewModel.updateCartQuantity(item.id, 1) },
            onUseClicked = { item -> onUseInventoryItem(item) },
            onItemDetailClicked = { item, ownedQty -> showItemDetailPopup(item, ownedQty) },
            onCartDeltaClicked = { item, delta -> viewModel.updateCartQuantity(item.id, delta) },
        )
        binding.rvShopItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShopItems.adapter = adapter
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.coins.collect { coins ->
                        binding.tvShopCoins.text = "🪙 %,d".format(coins)
                        renderCartAndCheckoutUi()
                    }
                }

                launch {
                    viewModel.diamonds.collect { diamonds ->
                        binding.tvShopDiamonds.text = "💎 %,d".format(diamonds)
                        renderCartAndCheckoutUi()
                    }
                }

                launch {
                    viewModel.hp.collect { hp ->
                        val maxHp = viewModel.maxHp.value
                        binding.tvShopHp.text = "❤️ $hp/$maxHp"
                    }
                }

                launch {
                    viewModel.maxHp.collect { maxHp ->
                        val hp = viewModel.hp.value
                        binding.tvShopHp.text = "❤️ $hp/$maxHp"
                    }
                }

                launch {
                    viewModel.screenMode.collect { mode ->
                        updateScreenModeUi(mode)
                    }
                }

                launch {
                    viewModel.filter.collect { filter ->
                        updateStoreHeader(filter)
                        renderCartAndCheckoutUi()
                    }
                }

                launch {
                    viewModel.cart.collect {
                        renderCartAndCheckoutUi()
                    }
                }

                launch {
                    viewModel.catalogItems.collect {
                        renderCartAndCheckoutUi()
                    }
                }

                launch {
                    viewModel.inventoryItems.collect { inv ->
                        val totalOwned = inv.sumOf { it.quantity }
                        binding.tvHubBagCount.text = if (totalOwned > 0) {
                            "$totalOwned Owned →"
                        } else {
                            "Empty →"
                        }
                    }
                }

                launch {
                    viewModel.uiItems.collect { items ->
                        adapter.submitList(items)
                        val isBagFilter = viewModel.filter.value == ShopFilter.BAG
                        if (items.isEmpty() && isBagFilter) {
                            binding.llShopEmpty.visibility = View.VISIBLE
                        } else {
                            binding.llShopEmpty.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.pbShopLoading.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.isCheckingOut.collect { checkingOut ->
                        updateCheckoutBlurOverlay(checkingOut)
                    }
                }

                launch {
                    viewModel.message.collect { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateScreenModeUi(mode: ShopScreenMode) {
        when (mode) {
            ShopScreenMode.HUB -> {
                (activity as? MainActivity)?.setBottomNavVisible(true)
                binding.btnShopBack.visibility = View.GONE
                binding.layoutShopHub.visibility = View.VISIBLE
                binding.layoutShopCatalog.visibility = View.GONE
                binding.layoutShopCheckout.visibility = View.GONE
                adapter.submitList(emptyList())
            }
            ShopScreenMode.STORE_CATALOG -> {
                (activity as? MainActivity)?.setBottomNavVisible(false)
                binding.btnShopBack.visibility = View.VISIBLE
                binding.btnShopBack.text = "← Hub"
                binding.layoutShopHub.visibility = View.GONE
                binding.layoutShopCatalog.visibility = View.VISIBLE
                binding.layoutShopCheckout.visibility = View.GONE
                binding.rvShopItems.scrollToPosition(0)
                updateStoreHeader(viewModel.filter.value)
            }
            ShopScreenMode.CHECKOUT -> {
                (activity as? MainActivity)?.setBottomNavVisible(false)
                binding.btnShopBack.visibility = View.VISIBLE
                binding.btnShopBack.text = "← Back"
                binding.layoutShopHub.visibility = View.GONE
                binding.layoutShopCatalog.visibility = View.GONE
                binding.layoutShopCheckout.visibility = View.VISIBLE
            }
        }
        renderCartAndCheckoutUi()
    }

    private fun updateStoreHeader(filter: ShopFilter) {
        when (filter) {
            ShopFilter.ELIXIR -> {
                binding.tvStoreTitle.text = "🧪 Crimson Apothecary (万灵丹坊)"
                binding.tvStoreSubtitle.text =
                    "Tap any potion for full stats & lore · Restores 25%–100% Max HP in battle"
            }
            ShopFilter.FOOD -> {
                binding.tvStoreTitle.text = "🍜 Imperial Pavilion Tavern (御膳酒楼)"
                binding.tvStoreSubtitle.text =
                    "Tap any dish for full stats & lore · Higher-tier dishes give up to 10.0 HP/Coin!"
            }
            ShopFilter.PET_FOOD -> {
                binding.tvStoreTitle.text = "🐾 Spirit Beast Menagerie (灵兽仙苑)"
                binding.tvStoreSubtitle.text =
                    "Tap any delicacy for full stats & lore · Higher-tier treats give more ❤️/Coin!"
            }
            ShopFilter.BAG -> {
                binding.tvStoreTitle.text = "🎒 Adventurer's Satchel (行囊背包)"
                binding.tvStoreSubtitle.text =
                    "Consume owned meals or potions to restore Hero HP before your next battle"
            }
            ShopFilter.ALL -> {
                binding.tvStoreTitle.text = "⛩️ Imperial Market Catalog"
                binding.tvStoreSubtitle.text =
                    "Tap any item for full stats & lore · Use (−) 1 (+) to add to cart"
            }
        }
    }

    private fun renderCartAndCheckoutUi() {
        val cartMap = viewModel.cart.value
        val catalogById = viewModel.catalogItems.value.associateBy { it.id }

        val totalQty = cartMap.values.sum()
        binding.btnShopCart.text = "🛒 $totalQty"

        var totalCoins = 0
        var totalDiamonds = 0
        var totalFlatHp = 0
        var totalPotionsCount = 0
        var totalAffection = 0

        for ((itemId, qty) in cartMap) {
            val item = catalogById[itemId] ?: continue
            totalCoins += item.priceCoins * qty
            totalDiamonds += item.priceDiamonds * qty
            totalFlatHp += item.hpRestore * qty
            if (item.hpRestorePct > 0) totalPotionsCount += qty
            totalAffection += item.affectionBoost * qty
        }

        val costParts = mutableListOf<String>()
        if (totalCoins > 0) costParts.add("%,d 🪙".format(totalCoins))
        if (totalDiamonds > 0) costParts.add("%,d 💎".format(totalDiamonds))
        val costText = if (costParts.isEmpty()) "0 🪙" else costParts.joinToString(" + ")

        // Floating checkout bar in Store Catalog
        val showFloatingBar = viewModel.screenMode.value == ShopScreenMode.STORE_CATALOG &&
            viewModel.filter.value != ShopFilter.BAG &&
            totalQty > 0
        binding.cardFloatingCheckoutBar.visibility = if (showFloatingBar) View.VISIBLE else View.GONE
        binding.tvFloatingCartSummary.text = "🛒 $totalQty Item(s) · $costText"

        // Populate Checkout Screen if visible
        if (viewModel.screenMode.value == ShopScreenMode.CHECKOUT) {
            populateCheckoutRows(cartMap, catalogById)
            binding.tvCheckoutTotalItems.text = "Total Provisions: $totalQty item(s)"

            val benefitParts = mutableListOf<String>()
            if (totalFlatHp > 0) benefitParts.add("+$totalFlatHp Hero HP")
            if (totalPotionsCount > 0) benefitParts.add("${totalPotionsCount}x % Max HP Battle Potion(s)")
            if (totalAffection > 0) benefitParts.add("+$totalAffection ❤️ Pet Affection")
            binding.tvCheckoutTotalHeal.text = if (benefitParts.isNotEmpty()) {
                "Total Value: ${benefitParts.joinToString(" · ")}"
            } else {
                "Total Value: —"
            }

            binding.tvCheckoutTotalCost.text = "Total Cost: $costText"

            val remCoins = viewModel.coins.value - totalCoins
            val remDiamonds = viewModel.diamonds.value - totalDiamonds
            val canAfford = remCoins >= 0 && remDiamonds >= 0
            binding.tvCheckoutBalanceAfter.text = if (canAfford) {
                "Balance After Payment: %,d 🪙 · %,d 💎".format(remCoins, remDiamonds)
            } else {
                "⚠️ Insufficient Funds! Balance After: %,d 🪙 · %,d 💎".format(remCoins, remDiamonds)
            }
            binding.tvCheckoutBalanceAfter.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (canAfford) R.color.hq_text_secondary else R.color.hq_crimson,
                ),
            )
            binding.btnConfirmCheckout.isEnabled = canAfford && totalQty > 0
        }
    }

    private fun populateCheckoutRows(
        cartMap: Map<String, Int>,
        catalogById: Map<String, ShopItem>,
    ) {
        val container = binding.llCheckoutItems
        container.removeAllViews()
        val ctx = requireContext()

        for ((itemId, qty) in cartMap) {
            val item = catalogById[itemId] ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val tvIcon = TextView(ctx).apply {
                text = item.iconEmoji
                textSize = 22f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_stat_box)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            }
            row.addView(tvIcon)

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                }
            }

            val tvTitle = TextView(ctx).apply {
                text = item.name
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                textSize = 13f
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            }
            infoCol.addView(tvTitle)

            val unitCost = if (item.priceCoins > 0) "${item.priceCoins} 🪙" else "${item.priceDiamonds} 💎"
            val subTotal = if (item.priceCoins > 0) {
                "${item.priceCoins * qty} 🪙"
            } else {
                "${item.priceDiamonds * qty} 💎"
            }
            val tvSub = TextView(ctx).apply {
                text = "${item.nameZh}  ·  $unitCost × $qty = $subTotal"
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                textSize = 11f
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoCol.addView(tvSub)
            row.addView(infoCol)

            // Stepper controls
            val stepper = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnMinus = TextView(ctx).apply {
                text = "−"
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                setBackgroundResource(R.drawable.bg_stat_box)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener { viewModel.updateCartQuantity(item.id, -1) }
            }
            val tvQty = TextView(ctx).apply {
                text = "$qty"
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
                minWidth = dp(26)
            }
            val btnPlus = TextView(ctx).apply {
                text = "+"
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
                setBackgroundResource(R.drawable.bg_stat_box)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener { viewModel.updateCartQuantity(item.id, 1) }
            }

            stepper.addView(btnMinus)
            stepper.addView(tvQty)
            stepper.addView(btnPlus)
            row.addView(stepper)

            container.addView(row)
        }
    }

    private fun updateCheckoutBlurOverlay(checkingOut: Boolean) {
        val b = _binding ?: return
        if (checkingOut) {
            (requireActivity().application as HskQuestApp).container.soundPlayer.play(Sfx.COIN)
            b.tvShopLoadingTip.text = loadingTips.random()
            b.layoutShopLoadingOverlay.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.shopMainContent.setRenderEffect(
                    RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP),
                )
            }
        } else {
            b.layoutShopLoadingOverlay.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.shopMainContent.setRenderEffect(null)
            }
        }
    }

    private fun showItemDetailPopup(item: ShopItem, ownedQty: Int) {
        detailSheet?.dismiss()
        detailAnimator?.stop()
        detailAnimator = null

        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_gold_border)
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
        }

        // Top row: Icon / Animated Sprite + Name & Category Tag
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconBox = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.bg_stat_box)
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        }

        val potionDrawableAndCols: Pair<Int, Int>? = when {
            item.spriteRes == "potion_minor_red" || item.id == "minor_healing_potion" ->
                R.drawable.potion_minor_red to 9
            item.spriteRes == "potion_regular_red" || item.id == "healing_potion" ->
                R.drawable.potion_regular_red to 7
            item.spriteRes == "potion_greater_red" || item.id == "greater_healing_potion" ->
                R.drawable.potion_greater_red to 8
            item.spriteRes == "potion_supreme_red" || item.id == "supreme_healing_potion" ->
                R.drawable.potion_supreme_red to 13
            else -> null
        }

        if (potionDrawableAndCols != null) {
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
            }
            iconBox.addView(iv)
            detailAnimator = SpriteAnimator.startHorizontalStripAnimation(
                imageView = iv,
                drawableRes = potionDrawableAndCols.first,
                cols = potionDrawableAndCols.second,
                fps = 8,
                scaleFactor = 3,
            )
        } else {
            val tvEmoji = TextView(ctx).apply {
                text = item.iconEmoji
                textSize = 30f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
            }
            iconBox.addView(tvEmoji)
        }
        headerRow.addView(iconBox)

        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }

        val tvName = TextView(ctx).apply {
            text = item.name
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            textSize = 16f
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        titleCol.addView(tvName)

        val tvNameZh = TextView(ctx).apply {
            text = item.nameZh
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
            textSize = 13f
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(1) }
        }
        titleCol.addView(tvNameZh)

        val categoryLabel = when (item.category) {
            "elixir" -> "🧪 Alchemical Battle Potion"
            "food" -> "🍜 Imperial Human Cuisine"
            "pet_food" -> "🐾 Companion Pet Delicacy"
            else -> "📦 Imperial Provision"
        }
        val categoryBadgeRes = when (item.category) {
            "elixir" -> R.drawable.ic_badge_potion
            "food" -> R.drawable.ic_badge_food
            "pet_food" -> R.drawable.ic_badge_pet_food
            else -> 0
        }
        val tvCategory = TextView(ctx).apply {
            text = categoryLabel
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_secondary))
            textSize = 11f
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (categoryBadgeRes != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(categoryBadgeRes, 0, 0, 0)
                compoundDrawablePadding = dp(6)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) }
        }
        titleCol.addView(tvCategory)
        headerRow.addView(titleCol)
        root.addView(headerRow)

        // Lore description
        val tvDesc = TextView(ctx).apply {
            text = item.description
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }
        root.addView(tvDesc)

        // Stats Breakdown Card
        val statsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_stat_box)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }

        val effectStr = buildString {
            if (item.hpRestorePct > 0) append("+${item.hpRestorePct}% Max HP (Usable Mid-Battle)")
            if (item.hpRestore > 0) {
                if (isNotEmpty()) append(" · ")
                append("+${item.hpRestore} Hero HP (Out-of-Battle Rest)")
            }
            if (item.affectionBoost > 0) {
                if (isNotEmpty()) append(" · ")
                append("+${item.affectionBoost} ❤️ Pet Affection")
            }
        }

        val valueEfficiencyStr = when {
            item.hpRestore > 0 && item.priceCoins > 0 ->
                "🔥 ${"%.1f".format(item.hpRestore.toFloat() / item.priceCoins)} HP per Coin"
            item.hpRestorePct > 0 ->
                "⚡ Scales with Hero Max HP (Mid-Battle)"
            item.affectionBoost > 0 && item.priceCoins > 0 ->
                "🔥 ${"%.1f".format(item.affectionBoost.toFloat() / item.priceCoins)} ❤️ per Coin"
            else -> "💎 Mythical Celestial Delicacy"
        }

        val priceStr = if (item.priceCoins > 0) {
            "${item.priceCoins} Coins 🪙"
        } else {
            "${item.priceDiamonds} Gems 💎"
        }

        val tvStatEffect = TextView(ctx).apply {
            text = "✨ Effect: $effectStr"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_jade))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvStatValue = TextView(ctx).apply {
            text = "📊 Value Efficiency: $valueEfficiencyStr"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        val tvStatPrice = TextView(ctx).apply {
            text = "💰 Unit Price: $priceStr   |   🎒 Owned in Satchel: ${ownedQty}x"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_secondary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }

        statsBox.addView(tvStatEffect)
        statsBox.addView(tvStatValue)
        statsBox.addView(tvStatPrice)
        root.addView(statsBox)

        // Interactive Cart Stepper Row inside Popup
        val stepperRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }

        val tvCartLabel = TextView(ctx).apply {
            text = "Quantity in Cart:"
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        stepperRow.addView(tvCartLabel)

        val tvPopupQty = TextView(ctx).apply {
            text = "${viewModel.cart.value[item.id] ?: 0}"
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            minWidth = dp(36)
        }

        val btnMinus = TextView(ctx).apply {
            text = "−"
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setBackgroundResource(R.drawable.bg_stat_box)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener {
                viewModel.updateCartQuantity(item.id, -1)
                tvPopupQty.text = "${viewModel.cart.value[item.id] ?: 0}"
            }
        }

        val btnPlus = TextView(ctx).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setBackgroundResource(R.drawable.bg_stat_box)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener {
                viewModel.updateCartQuantity(item.id, 1)
                tvPopupQty.text = "${viewModel.cart.value[item.id] ?: 0}"
            }
        }

        stepperRow.addView(btnMinus)
        stepperRow.addView(tvPopupQty)
        stepperRow.addView(btnPlus)
        root.addView(stepperRow)

        // Action Buttons Row (Close & Checkout)
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }

        val btnClose = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Continue Shopping"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { sheet.dismiss() }
        }

        val btnCheckoutNow = MaterialButton(ctx).apply {
            text = "Checkout 🛒"
            textSize = 12f
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hq_gold))
            setTextColor(ContextCompat.getColor(ctx, R.color.hq_bg_deep))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                if ((viewModel.cart.value[item.id] ?: 0) == 0) {
                    viewModel.updateCartQuantity(item.id, 1)
                }
                sheet.dismiss()
                viewModel.openCheckout()
            }
        }

        btnRow.addView(btnClose)
        btnRow.addView(btnCheckoutNow)
        root.addView(btnRow)

        sheet.setOnDismissListener {
            detailAnimator?.stop()
            detailAnimator = null
            if (detailSheet === sheet) {
                detailSheet = null
            }
        }

        detailSheet = sheet
        sheet.setContentView(root)
        sheet.show()
    }

    private fun onUseInventoryItem(item: InventoryItem) {
        viewModel.useItem(item)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        startMerchantAnimation()
        viewModel.loadData()
    }

    override fun onPause() {
        merchantAnimator?.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setBottomNavVisible(true)
        detailSheet?.dismiss()
        detailSheet = null
        detailAnimator?.stop()
        detailAnimator = null
        merchantAnimator?.stop()
        merchantAnimator = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            _binding?.shopMainContent?.setRenderEffect(null)
        }
        super.onDestroyView()
        _binding = null
    }
}
