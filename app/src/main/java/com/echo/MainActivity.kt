package com.echo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.echo.core.ai.SimpleGemmaEngine
import com.echo.ui.theme.EchoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class Screen(val title: String, val icon: ImageVector) {
    object Home : Screen("Home", Icons.Default.Home)
    object Chat : Screen("Chat", Icons.Default.Chat)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var gemmaEngine: SimpleGemmaEngine
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Permission result handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request storage permission for model access
        requestStoragePermission()

        setContent {
            EchoTheme {
                EchoApp(gemmaEngine = gemmaEngine)
            }
        }
    }
    
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storagePermissionLauncher.launch(intent)
                }
            }
        } else {
            // For older versions
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cleanup AI engine resources
        gemmaEngine.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoApp(gemmaEngine: SimpleGemmaEngine) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Echo",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                
                NavigationDrawerItem(
                    icon = { Icon(Screen.Home.icon, contentDescription = null) },
                    label = { Text(Screen.Home.title) },
                    selected = currentScreen is Screen.Home,
                    onClick = {
                        currentScreen = Screen.Home
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                
                NavigationDrawerItem(
                    icon = { Icon(Screen.Chat.icon, contentDescription = null) },
                    label = { Text(Screen.Chat.title) },
                    selected = currentScreen is Screen.Chat,
                    onClick = {
                        currentScreen = Screen.Chat
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentScreen.title) },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (currentScreen) {
                    is Screen.Home -> HomeScreen()
                    is Screen.Chat -> ChatScreen(gemmaEngine = gemmaEngine)
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Productivity graphs coming soon...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun ChatScreen(gemmaEngine: SimpleGemmaEngine) {
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Clear chat button bar
        if (messages.isNotEmpty()) {
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            messages = emptyList()
                            gemmaEngine.clearConversation()
                        },
                        enabled = !isGenerating
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear chat",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Chat")
                    }
                }
            }
        }
        
        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Start chatting with Echo...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            
            items(messages) { message ->
                ChatBubble(message = message)
            }
        }

        // Input area
        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    enabled = !isGenerating,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp)
                )

                FilledIconButton(
                    onClick = {
                        if (isGenerating) {
                            // Stop generation - cancel at native level
                            gemmaEngine.cancelGeneration()
                            generationJob?.cancel()
                            generationJob = null
                            isGenerating = false
                        } else if (inputText.isNotBlank()) {
                            val userMessage = inputText.trim()
                            messages = messages + ChatMessage(userMessage, isUser = true)
                            inputText = ""
                            isGenerating = true

                            generationJob = scope.launch {
                                listState.animateScrollToItem(messages.size)
                                
                                // Add placeholder for AI response
                                var currentResponse = ""
                                messages = messages + ChatMessage("", isUser = false)
                                val aiMessageIndex = messages.size - 1
                                
                                if (gemmaEngine.isReady()) {
                                    // Stream tokens as they arrive
                                    gemmaEngine.chatStreaming(userMessage) { token ->
                                        // Check if job was cancelled
                                        if (isActive) {
                                            currentResponse += token
                                            // Update message in real-time
                                            messages = messages.toMutableList().apply {
                                                this[aiMessageIndex] = ChatMessage(currentResponse, isUser = false)
                                            }
                                        }
                                    }
                                } else {
                                    messages = messages.toMutableList().apply {
                                        this[aiMessageIndex] = ChatMessage("AI is still loading. Please wait...", isUser = false)
                                    }
                                }
                                
                                isGenerating = false
                                listState.animateScrollToItem(messages.size - 1)
                            }
                        }
                    },
                    enabled = inputText.isNotBlank() || isGenerating,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    if (isGenerating) {
                        // Small filled rectangle (like Copilot's stop button)
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
        ) {
            if (!message.isUser) {
                // AI avatar
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "E",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isUser) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(
                    topStart = if (message.isUser) 18.dp else 4.dp,
                    topEnd = if (message.isUser) 4.dp else 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp
                ),
                modifier = Modifier.widthIn(max = 320.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (message.isUser) 0.dp else 1.dp)
            ) {
                if (message.isUser) {
                    // User messages - simple text
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    // AI messages - formatted with headings and lists
                    Column(modifier = Modifier.padding(14.dp)) {
                        val lines = message.text.split("\n")
                        lines.forEachIndexed { index, line ->
                            val trimmed = line.trim()
                            
                            when {
                                // Emoji headings or numbered headings (🥇 1. HEADING)
                                trimmed.matches(Regex("^[🥇🥈🥉✅❌⚡🎯🔥💡📌⭐🚀🏆].*")) || 
                                trimmed.matches(Regex("^\\d+\\.\\s+[A-Z].*")) -> {
                                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = trimmed.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1"),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                // Bold headings (**text**)
                                trimmed.matches(Regex("^\\*\\*.*\\*\\*$")) -> {
                                    if (index > 0) Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = trimmed.replace("**", ""),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                // Bullet points
                                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                                    Row {
                                        Text("• ", style = MaterialTheme.typography.bodyLarge)
                                        // Parse bold within bullet text
                                        Text(
                                            text = buildAnnotatedString {
                                                var remaining = trimmed.substring(2)
                                                val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
                                                var lastIndex = 0
                                                
                                                boldRegex.findAll(remaining).forEach { match ->
                                                    // Add text before bold
                                                    append(remaining.substring(lastIndex, match.range.first))
                                                    // Add bold text
                                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                        append(match.groupValues[1])
                                                    }
                                                    lastIndex = match.range.last + 1
                                                }
                                                // Add remaining text
                                                append(remaining.substring(lastIndex))
                                            },
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                                // Normal text with inline bold
                                trimmed.isNotEmpty() -> {
                                    Text(
                                        text = buildAnnotatedString {
                                            var remaining = trimmed
                                            val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
                                            var lastIndex = 0
                                            
                                            boldRegex.findAll(remaining).forEach { match ->
                                                // Add text before bold
                                                append(remaining.substring(lastIndex, match.range.first))
                                                // Add bold text
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append(match.groupValues[1])
                                                }
                                                lastIndex = match.range.last + 1
                                            }
                                            // Add remaining text
                                            append(remaining.substring(lastIndex))
                                        },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                // Empty lines for spacing
                                else -> {
                                    if (index > 0 && index < lines.size - 1) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (message.isUser) {
                Spacer(modifier = Modifier.width(8.dp))
                // User avatar
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "U",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}


