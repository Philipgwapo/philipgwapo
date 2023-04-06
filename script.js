// Your web app's Firebase configuration
const firebaseConfig = {
  apiKey: "AIzaSyBHUMrQ2sP6XkDpJJRDHHTbrXQYUgTR7gw",
  authDomain: "twitter-for-rising.firebaseapp.com",
  databaseURL: "https://twitter-for-rising-default-rtdb.firebaseio.com",
  projectId: "twitter-for-rising",
  storageBucket: "twitter-for-rising.appspot.com",
  messagingSenderId: "655192849459",
  appId: "1:655192849459:web:b2bbc52a02ff9ef2fcb746",
  measurementId: "G-ZR6DX536ZZ",
};

// Initialize Firebase
const app = firebase.initializeApp(firebaseConfig);
const database = firebase.database();

const tweetForm = document.getElementById("tweet-form");
const tweetText = document.getElementById("tweet-text");
const tweetsContainer = document.getElementById("tweets-container");

// Save tweet to Firebase
function saveTweet(tweetContent) {
  const newTweetRef = database.ref("tweets").push();
  newTweetRef.set({
    content: tweetContent,
  });
}

// Load tweets from Firebase
function loadTweets() {
  const tweetsRef = database.ref("tweets");
  tweetsRef.on("child_added", (snapshot) => {
    const tweetElement = document.createElement("div");
    tweetElement.textContent = snapshot.val().content;
    tweetsContainer.prepend(tweetElement);
  });
}

// Event listener for tweet form submission
tweetForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const tweetContent = tweetText.value.trim();
  if (tweetContent) {
    saveTweet(tweetContent);
    tweetText.value = "";
  }
});

// Load tweets when the page loads
loadTweets();
