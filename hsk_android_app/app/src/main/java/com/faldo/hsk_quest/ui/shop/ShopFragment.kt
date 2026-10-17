package com.faldo.hsk_quest.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.faldo.hsk_quest.HskQuestApp
import com.faldo.hsk_quest.R
import com.faldo.hsk_quest.data.model.InventoryItem
import com.faldo.hsk_quest.data.model.ShopItem
import com.faldo.hsk_quest.databinding.FragmentShopBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Ancient Market / Toko Makanan screen where adventurers buy food for companion pets
 * and elixirs for HP recovery, and manage their consumable bag.
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

        setupRecyclerView()
        setupFilterChips()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ShopAdapter(
            onBuyClicked = { item -> showBuyConfirmationDialog(item) },
            onUseClicked = { item -> onUseInventoryItem(item) },
        )
        binding.rvShopItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShopItems.adapter = adapter
    }

    private fun setupFilterChips() {
        binding.chipGroupShop.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chip_all -> viewModel.setFilter(ShopFilter.ALL)
                R.id.chip_food -> viewModel.setFilter(ShopFilter.FOOD)
                R.id.chip_elixirs -> viewModel.setFilter(ShopFilter.ELIXIR)
                R.id.chip_bag -> viewModel.setFilter(ShopFilter.BAG)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.coins.collect { coins ->
                        binding.tvShopCoins.text = "🪙 %,d Coins".format(coins)
                    }
                }

                launch {
                    viewModel.diamonds.collect { diamonds ->
                        binding.tvShopDiamonds.text = "💎 %,d Gems".format(diamonds)
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
                    viewModel.message.collect { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showBuyConfirmationDialog(item: ShopItem) {
        val costString = if (item.priceCoins > 0) {
            "${item.priceCoins} Coins 🪙"
        } else {
            "${item.priceDiamonds} Gems 💎"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("${item.iconEmoji} Purchase ${item.name}")
            .setMessage(
                "Buy 1x ${item.name} (${item.nameZh}) for $costString?\n\n" +
                        "Effects: ${if (item.hpRestore > 0) "+${item.hpRestore} HP " else ""}" +
                        "${if (item.affectionBoost > 0) "+${item.affectionBoost} Pet Affection ❤️" else ""}"
            )
            .setPositiveButton("Buy") { _, _ ->
                viewModel.buyItem(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onUseInventoryItem(item: InventoryItem) {
        viewModel.useItem(item)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
